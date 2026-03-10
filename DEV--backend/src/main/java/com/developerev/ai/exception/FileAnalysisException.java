package com.developerev.ai.exception;

/**
 * Thrown when the system is unable to read, scan, or pre-process project files
 * before sending them to the AI service.
 * Not retryable — the issue is with the file content or access, not the AI service.
 */
public class FileAnalysisException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "Unable to analyze the project files.";

    public FileAnalysisException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, false);
    }

    public FileAnalysisException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, false, cause);
    }
}
