package com.developerev.ai.service;

import com.developerev.ai.dto.ProviderResponse;
import com.developerev.ai.prompt.PromptBuilder;
import com.developerev.ai.prompt.PromptType;
import com.developerev.ai.provider.LLMProvider;
import com.developerev.ai.tracker.TokenUsageTracker;
import com.developerev.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIService {

    // Spring automatically injects all beans implementing LLMProvider
    // Example: GeminiProviderImpl, OpenAIProviderImpl
    private final List<LLMProvider> providers;

    private final PromptBuilder promptBuilder;
    private final TokenUsageTracker tokenTracker;
    // Note: If using the proxy method 'askAI', we need a way to look up the user.
    // In our current architecture, processRequest relies on passing the User object
    // directly.
    // For the requested signature, we add this wrapper:
    private final com.developerev.repository.UserRepository userRepository;

    public String askAI(PromptType type, String input, String providerName, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return processRequest(user, type, input, providerName);
    }

    /**
     * Orchestrates the execution of an AI task.
     * 
     * @param user              The authenticated user making the request
     * @param featureType       The specific AI capability requested (e.g.,
     *                          CODE_REVIEW)
     * @param userContext       The code/logs/data payload
     * @param preferredProvider User's requested LLM. If null/invalid, defaults to
     *                          Gemini.
     * @return Raw content returned by the LLM
     */
    public String processRequest(User user, PromptType featureType, String userContext, String preferredProvider) {

        // 1. Pre-Execution Quote Check
        if (tokenTracker.hasExceededQuota(user)) {
            // Throwing a custom exception ensures controller advice can return HTTP 429 Too
            // Many Requests
            throw new RuntimeException("AI token quota exceeded for this billing cycle.");
        }

        // 2. Resolve the Strategy (Provider)
        LLMProvider provider = resolveProvider(preferredProvider);

        // 3. Build the prompt using the builder pattern
        // The provider interface expects the promptType and context, and formatting is
        // done inside

        try {
            log.info("Executing {} AI Request via {} for user: {}", featureType, provider.getProviderName(),
                    user.getUsername());

            // 4. Execute the call (In production, wrap this in a Resilience4j
            // CircuitBreaker)
            ProviderResponse response = provider.generateContent(featureType, userContext);

            // 5. Post-Execution Token Logging
            tokenTracker.logUsage(
                    user,
                    featureType,
                    response.getPromptTokens(),
                    response.getCompletionTokens(),
                    response.getTotalTokens(),
                    response.getProviderName());

            return response.getContent();

        } catch (Exception e) {
            log.error("AI Provider execution failed", e);
            throw new RuntimeException("AI provider failed to generate response: " + e.getMessage(), e);
        }
    }

    /**
     * Strategy resolver. Dynamic routing without changing core logic.
     */
    private LLMProvider resolveProvider(String providerName) {
        if (providerName == null || providerName.trim().isEmpty()) {
            return getDefaultProvider();
        }

        return providers.stream()
                .filter(p -> p.getProviderName().equalsIgnoreCase(providerName))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Requested provider '{}' not found. Falling back to default.", providerName);
                    return getDefaultProvider();
                });
    }

    private LLMProvider getDefaultProvider() {
        return providers.stream()
                .filter(p -> p.getProviderName().equals("GEMINI"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Default GEMINI provider not registered"));
    }
}
