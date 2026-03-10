package com.developerev.ai.exception;

/**
 * Thrown when the Gemini API returns a null or empty response body.
 * Not retryable — indicates an unexpected API behavior that requires investigation.
 */
public class EmptyAiResponseException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "AI service returned an empty response.";

    public EmptyAiResponseException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, false);
    }

    public EmptyAiResponseException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, false, cause);
    }
}
