package com.developerev.ai.exception;

/**
 * Thrown when the Gemini API returns HTTP 429 Too Many Requests.
 * This error is retryable after a short back-off delay.
 */
public class RateLimitExceededException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "AI service request limit reached. Please retry after a short delay.";

    public RateLimitExceededException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, true);
    }

    public RateLimitExceededException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, true, cause);
    }
}
