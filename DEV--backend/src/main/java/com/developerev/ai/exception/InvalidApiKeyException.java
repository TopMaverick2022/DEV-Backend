package com.developerev.ai.exception;

/**
 * Thrown when the Gemini API rejects the API key (HTTP 401 or 403).
 * Not retryable — requires an administrator to verify/rotate the API key.
 */
public class InvalidApiKeyException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "AI service authentication failed. Please verify the API key configuration.";

    public InvalidApiKeyException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, false);
    }

    public InvalidApiKeyException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, false, cause);
    }
}
