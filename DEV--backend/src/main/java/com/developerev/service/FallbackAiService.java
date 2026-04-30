package com.developerev.service;

import com.developerev.ai.exception.AiServiceUnavailableException;
import com.developerev.ai.exception.DailyQuotaExceededException;
import com.developerev.ai.exception.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Acts as the primary AI client gateway.
 * It holds a list of configured AiClient implementations (e.g., Gemini, OpenAI)
 * and automatically falls back to the next available provider if one fails with a quota limit.
 */
@Slf4j
@Primary
@Service
public class FallbackAiService implements AiClient {

    private final List<AiClient> providers;

    /**
     * Spring will inject all available beans that implement AiClient.
     * We filter out the FallbackAiService itself to avoid infinite recursion.
     */
    public FallbackAiService(List<AiClient> allClients) {
        this.providers = allClients.stream()
                .filter(client -> !(client instanceof FallbackAiService))
                .toList();
                
        if (this.providers.isEmpty()) {
            log.warn("No AiClient implementations found! AI features will fail.");
        } else {
            log.info("Initialized FallbackAiService with {} providers.", this.providers.size());
        }
    }

    @Override
    public String generateContent(String prompt) {
        if (providers.isEmpty()) {
            throw new AiServiceUnavailableException("No AI providers are configured.");
        }

        Throwable lastException = null;

        for (int i = 0; i < providers.size(); i++) {
            AiClient provider = providers.get(i);
            String providerName = provider.getClass().getSimpleName();
            
            try {
                log.debug("[AI_ROUTING] Attempting to use provider: {}", providerName);
                return provider.generateContent(prompt);
                
            } catch (DailyQuotaExceededException | RateLimitExceededException e) {
                log.error("[AI_FALLBACK] Provider {} failed due to quota/rate limit: {}. Trying next provider if available.", providerName, e.getMessage());
                lastException = e;
            } catch (Exception e) {
                log.error("[AI_FALLBACK] Provider {} failed with an unexpected error: {}. Trying next provider if available.", providerName, e.getMessage());
                lastException = e;
            }
        }

        // If we loop through all providers and they all fail
        if (lastException instanceof com.developerev.ai.exception.GeminiApiException gae) {
            throw gae;
        }
        
        throw new AiServiceUnavailableException("All AI providers failed to fulfill the request. Last error: " + 
                (lastException != null ? lastException.getMessage() : "Unknown"), lastException);
    }
}
