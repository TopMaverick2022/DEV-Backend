package com.developerev.ai.prompt;

import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

    /**
     * Assembles the final prompt combining system instructions with user context.
     * In a more complex setup, this could use Freemarker or Velocity templates.
     */
    public String buildPrompt(PromptType type, String userContext) {
        return String.format(
                "==== SYSTEM INSTRUCTIONS ====\n%s\n\n==== USER CONTEXT ====\n%s\n\n==== PLEASE PROVIDE YOUR RESPONSE BELOW ====",
                type.getSystemPrompt(),
                userContext);
    }
}
