package com.developerev.ai.provider;

import com.developerev.ai.dto.ProviderResponse;
import com.developerev.ai.prompt.PromptType;

public interface LLMProvider {
    /**
     * Executes the generation request against the specific LLM API.
     *
     * @param promptType  The enum specifying the system rules and persona.
     * @param userContext The actual data to process (code, logs, user message).
     * @return DTO containing raw string response from the AI and token metadata.
     */
    ProviderResponse generateContent(PromptType promptType, String userContext);

    /**
     * Identifies the provider for factory selection (e.g., "GEMINI", "OPENAI").
     */
    String getProviderName();
}
