package com.developerev.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Maps the raw JSON that Gemini returns for dependency detection:
 *
 * {
 * "dependencies": [
 * { "taskId": 3, "dependsOn": 1 }
 * ]
 * }
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DependencyAiResponseDto {

    private List<DependencyItem> dependencies;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DependencyItem {

        /** The task that depends on another task (cannot start yet). */
        private Long taskId;

        /** The task that must be completed first. */
        private Long dependsOn;
    }
}
