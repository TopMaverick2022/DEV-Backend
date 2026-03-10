package com.developerev.ai.exception;

/**
 * Thrown when the Gemini API daily usage quota has been fully consumed.
 * Not retryable — callers should try again the following day.
 */
public class DailyQuotaExceededException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "Daily AI processing limit reached. Please try again tomorrow.";

    public DailyQuotaExceededException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, false);
    }

    public DailyQuotaExceededException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, false, cause);
    }
}
