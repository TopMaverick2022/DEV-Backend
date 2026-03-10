package com.developerev.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Per-file AI code review result returned by POST /ai/code-review.
 *
 * Example:
 * {
 * "filename": "app.py",
 * "path": "src/app.py",
 * "language": "Python",
 * "issues": [
 * { "line": 12, "type": "Security", "message": "Hardcoded secret key" }
 * ],
 * "suggestions": [
 * { "line": 12, "message": "Use environment variable for secrets" }
 * ],
 * "architectureInsights": [
 * "Module lacks separation of concerns — business logic mixed with I/O"
 * ]
 * }
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileReviewDto {

    private String filename;
    private String path;

    /** Detected language (e.g. "Python", "Java", "TypeScript"). */
    private String language;

    private List<IssueDto> issues;
    private List<SuggestionDto> suggestions;

    /** High-level architecture / design observations for this file. */
    private List<String> architectureInsights;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IssueDto {
        private Integer line;
        private String type;
        private String message;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SuggestionDto {
        private Integer line;
        private String message;
    }
}
