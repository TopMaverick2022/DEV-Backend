package com.developerev.ai.exception;

/**
 * Thrown when the Gemini API is temporarily unavailable (HTTP 500 or 503).
 * NOT retryable at the HTTP client level — "high demand" 503s do not resolve
 * within a short backoff window. The user should be notified immediately and
 * retry the request manually after a short wait.
 */
public class AiServiceUnavailableException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "The Gemini AI service is temporarily under high demand. Please try again in a few seconds.";

    public AiServiceUnavailableException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, false); // retryable = false → fail fast, no silent hanging
    }

    public AiServiceUnavailableException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, false, cause);
    }
}
