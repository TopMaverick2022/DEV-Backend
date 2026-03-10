package com.developerev.service;

import org.springframework.stereotype.Service;

/**
 * Builds AI prompts for code review.
 *
 * Produces a language-aware prompt that instructs Gemini to return
 * ONLY a structured JSON object with issues, suggestions, and
 * architecture insights for the given file.
 */
@Service
public class AiPromptBuilder {

    /**
     * Builds a language-aware review prompt for a single source file.
     *
     * @param filename just the filename (e.g. "ProductService.java")
     * @param language detected language (e.g. "Java", "Python", "Unknown")
     * @param content  file content (already truncated to safe size)
     * @return prompt string ready to be sent to Gemini
     */
    public String buildPrompt(String filename, String language, String content) {
        return """
                You are a senior software architect and AI code reviewer.

                Analyze the following %s file named '%s'.

                Apply %s-specific best practices:
                - Java: Spring patterns, JPA usage, memory management, exception handling, SOLID principles
                - Python: PEP8, type hints, logging, security (avoid eval/exec), dependency management
                - JavaScript/TypeScript: ES standards, async/await patterns, XSS/SQL injection prevention, module design
                - C#: .NET best practices, async/await, IDisposable, memory management
                - PHP: SQL injection prevention, modern PHP 8 standards, input sanitization
                - Go: goroutine safety, idiomatic error handling, interface design
                - HTML/CSS: accessibility, semantic structure, performance
                - SQL: injection risks, index usage, query performance
                - Shell: error handling, quoting, portability
                - Config files (YAML/JSON/XML): correctness, secret exposure, structure
                - For any other language: apply general software engineering best practices

                Rules:
                - Do NOT modify the code
                - Only report issues, suggestions, and insights
                - Include line numbers wherever possible
                - Return ONLY valid JSON — no markdown, no code fences, no extra text

                Required JSON format:
                {
                  "issues": [
                    { "line": 12, "type": "Bug|Security|Performance|CodeQuality", "message": "Description of the issue" }
                  ],
                  "suggestions": [
                    { "line": 25, "message": "Suggestion for improvement" }
                  ],
                  "architectureInsights": [
                    "High-level observation about structure, coupling, patterns, or design..."
                  ]
                }

                File: %s
                Language: %s
                Code:
                ```
                %s
                ```
                """
                .formatted(language, filename, language, filename, language, content);
    }
}
