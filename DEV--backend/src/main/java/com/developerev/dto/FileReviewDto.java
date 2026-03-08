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
 * "filename": "TaskController.java",
 * "path": "src/main/java/com/example/TaskController.java",
 * "issues": [
 * { "line": 45, "type": "Security", "message": "SQL query built using string
 * concatenation" }
 * ],
 * "suggestions": [
 * { "line": 45, "message": "Use prepared statements" }
 * ]
 * }
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileReviewDto {

    private String filename;
    private String path;
    private List<IssueDto> issues;
    private List<SuggestionDto> suggestions;

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
