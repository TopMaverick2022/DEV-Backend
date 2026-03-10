package com.developerev.ai.exception;

/**
 * Catch-all exception for any unexpected system error that occurs during AI analysis.
 * Not retryable — requires investigation of the root cause from server logs.
 */
public class UnexpectedAiException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "An unexpected system error occurred during AI analysis.";

    public UnexpectedAiException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, false);
    }

    public UnexpectedAiException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, false, cause);
    }
}
