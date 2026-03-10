package com.developerev.ai.exception;

/**
 * Thrown when the connection to the Gemini API times out (connect or read timeout).
 * Retryable — the service may be momentarily overloaded.
 */
public class NetworkTimeoutException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "AI service did not respond in time. Please retry the operation.";

    public NetworkTimeoutException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, true);
    }

    public NetworkTimeoutException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, true, cause);
    }
}
