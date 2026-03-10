package com.developerev.ai.exception;

/**
 * Thrown when an LLM provider fails to respond within the configured timeout window.
 * Retryable — the LLM provider may be temporarily overloaded.
 *
 * <p>Extends {@link GeminiApiException} so it is automatically handled by the
 * {@code GlobalExceptionHandler} alongside all other AI exceptions.
 */
public class ProviderTimeoutException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "AI service did not respond in time. Please retry the operation.";

    public ProviderTimeoutException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, true);
    }

    public ProviderTimeoutException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, true, cause);
    }
}
