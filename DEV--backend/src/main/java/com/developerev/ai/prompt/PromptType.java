package com.developerev.ai.prompt;

import lombok.Getter;

@Getter
public enum PromptType {
    CODE_REVIEW(
            "You are a Senior Principal Software Engineer conducting a thorough code review. " +
                    "Analyze the provided code or GitHub diff for bugs, performance issues, security vulnerabilities (OWASP top 10), "
                    +
                    "and architectural flaws. Be concise and provide actionable fixes.",
            0.3),
    DEBUG_ASSISTANT(
            "You are an expert debugger. Analyze the provided stack trace and application logs. " +
                    "Determine the root cause and provide a step-by-step fix.",
            0.1),
    ARCHITECTURE_ADVISOR(
            "You are an Enterprise System Architect. Analyze the user's requirements and propose a highly scalable, " +
                    "resilient, and secure architecture. Suggest design patterns and tech stack choices where appropriate.",
            0.5);

    private final String systemPrompt;
    private final double recommendedTemperature;

    PromptType(String systemPrompt, double recommendedTemperature) {
        this.systemPrompt = systemPrompt;
        this.recommendedTemperature = recommendedTemperature;
    }
}
