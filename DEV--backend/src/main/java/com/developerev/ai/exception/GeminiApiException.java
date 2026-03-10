package com.developerev.ai.exception;

/**
 * Base exception for all Gemini API and AI-related errors.
 *
 * <p>Every subclass carries:
 * <ul>
 *   <li>{@code userMessage}  – clean, human-readable message safe to return in an API response</li>
 *   <li>{@code debugMessage} – internal detail intended only for server-side logs</li>
 *   <li>{@code retryable}    – whether the caller may safely retry the operation</li>
 * </ul>
 */
public abstract class GeminiApiException extends RuntimeException {

    private final String userMessage;
    private final String debugMessage;
    private final boolean retryable;

    protected GeminiApiException(String userMessage, String debugMessage, boolean retryable) {
        super(debugMessage);
        this.userMessage  = userMessage;
        this.debugMessage = debugMessage;
        this.retryable    = retryable;
    }

    protected GeminiApiException(String userMessage, String debugMessage, boolean retryable, Throwable cause) {
        super(debugMessage, cause);
        this.userMessage  = userMessage;
        this.debugMessage = debugMessage;
        this.retryable    = retryable;
    }

    public String getUserMessage()  { return userMessage; }
    public String getDebugMessage() { return debugMessage; }
    public boolean isRetryable()    { return retryable; }
}
