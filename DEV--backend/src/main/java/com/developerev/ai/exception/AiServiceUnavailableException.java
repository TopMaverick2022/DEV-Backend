package com.developerev.ai.exception;

/**
 * Thrown when the Gemini API is temporarily unavailable (HTTP 500 or 503).
 * Now marked as retryable so the GeminiClient can use exponential backoff
 * to recover from transient demand spikes.
 */
public class AiServiceUnavailableException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "The Gemini AI service is temporarily under high demand. Retrying...";

    public AiServiceUnavailableException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, true); // retryable = true → allow backoff/retry
    }

    public AiServiceUnavailableException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, true, cause);
    }
}
