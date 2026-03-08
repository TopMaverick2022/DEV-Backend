package com.developerev.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Maps the Gemini sprint JSON response.
 *
 * Expected format:
 * {
 * "sprints": [
 * {
 * "name": "Sprint 1",
 * "tasks": ["exact task title 1", "exact task title 2"]
 * }
 * ]
 * }
 */
@Data
@NoArgsConstructor
public class SprintAiResponseDto {

    private List<SprintItem> sprints;

    @Data
    @NoArgsConstructor
    public static class SprintItem {
        private String name;
        private int sprintNumber;
        private List<TaskRef> tasks;
    }

    @Data
    @NoArgsConstructor
    public static class TaskRef {
        private Long taskId;
    }
}
