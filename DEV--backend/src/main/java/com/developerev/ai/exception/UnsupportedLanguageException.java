package com.developerev.ai.exception;

/**
 * Thrown when the detected programming language of a project is not currently
 * supported by the AI analysis pipeline.
 * Not retryable — requires platform support for that language to be added.
 */
public class UnsupportedLanguageException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "The detected project language is currently unsupported.";

    public UnsupportedLanguageException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, false);
    }

    public UnsupportedLanguageException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, false, cause);
    }
}
