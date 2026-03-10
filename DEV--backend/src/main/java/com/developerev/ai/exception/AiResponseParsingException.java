package com.developerev.ai.exception;

/**
 * Thrown when the Gemini API response body cannot be parsed into the expected JSON structure.
 * Not retryable — indicates a structural change in the API response format.
 */
public class AiResponseParsingException extends GeminiApiException {

    private static final String USER_MESSAGE =
            "AI response format is invalid. Unable to process the result.";

    public AiResponseParsingException(String debugMessage) {
        super(USER_MESSAGE, debugMessage, false);
    }

    public AiResponseParsingException(String debugMessage, Throwable cause) {
        super(USER_MESSAGE, debugMessage, false, cause);
    }
}
