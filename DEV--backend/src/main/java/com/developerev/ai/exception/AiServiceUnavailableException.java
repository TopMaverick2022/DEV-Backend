package com.developerev.ai.exception;

/**
 * Thrown when the Gemini API is temporarily unavailable (HTTP 500 or 503).
 * Retryable — the service is likely undergoing maintenance or experiencing downtime.
 */
public class AiServiceUnavailableException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "AI service is temporarily unavailable. Please retry later.";

    public AiServiceUnavailableException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, true);
    }

    public AiServiceUnavailableException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, true, cause);
    }
}
