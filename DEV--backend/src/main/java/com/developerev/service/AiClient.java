package com.developerev.service;

/**
 * Common interface for AI providers (Gemini, OpenAI, etc.).
 */
public interface AiClient {

    /**
     * Sends a prompt to the AI provider and returns the extracted text response.
     * Implementations must handle provider-specific JSON envelopes internally.
     *
     * @param prompt the prompt to send
     * @return the raw text output from the model
     * @throws RuntimeException (or specific subclasses) if quota is exceeded or service is unavailable
     */
    String generateContent(String prompt);
}
