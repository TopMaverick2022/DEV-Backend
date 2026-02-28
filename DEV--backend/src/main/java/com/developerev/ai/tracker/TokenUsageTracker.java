package com.developerev.ai.tracker;

import com.developerev.ai.prompt.PromptType;
import com.developerev.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TokenUsageTracker {

    private static final int MONTHLY_FREE_TIER_LIMIT = 50_000;

    /**
     * Highly scalable systems use Redis (e.g., Bucket4j) for rate limiting.
     * Here we represent the quota logic.
     */
    public boolean hasExceededQuota(User user) {
        // In a real system, query the 'ai_token_ledgers' table or a Redis counter
        // for the sum of tokens used by this user in the current billing cycle.

        long currentUsage = getCurrentMonthlyUsage(user);

        if (currentUsage >= MONTHLY_FREE_TIER_LIMIT) {
            log.warn("User {} has exceeded their AI token quota ({} tokens used)", user.getUsername(), currentUsage);
            return true;
        }
        return false;
    }

    /**
     * Logs the executed usage to the database asynchronously.
     */
    public void logUsage(User user, PromptType promptType, int promptTokens, int completionTokens, int totalTokens,
            String provider) {
        // In a real system:
        // 1. Create a TokenLedger entity
        // 2. Save to ai_token_ledgers table
        // 3. Publish an async event to update Redis counters for real-time quota checks

        log.info(
                "AI Usage Logged -> User: {}, Feature: {}, Provider: {}, Prompt Tokens: {}, Completion Tokens: {}, Total: {}",
                user.getUsername(),
                promptType.name(),
                provider,
                promptTokens,
                completionTokens,
                totalTokens);
    }

    private long getCurrentMonthlyUsage(User user) {
        // Dummy implementation for now. Replace with real DB query.
        return 0L;
    }
}
