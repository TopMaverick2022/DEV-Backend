package com.developerev.dto;

import lombok.Data;
import java.util.List;

@Data
public class ProjectPlanResponseDto {
    private Long featureId;
    private String featureName;
    private String complexity;
    private Integer totalEstimatedHours;
    private List<String> detectedNeeds;
    private List<TaskDto> tasks;

    /**
     * AI-assessed implementation status of this feature:
     *   FRESH    – no existing code detected, safe to create from scratch
     *   PARTIAL  – feature is partially implemented; tasks cover the remaining balance
     *   EXISTS   – feature appears to already be fully or substantially implemented
     */
    private String analysisStatus;

    /**
     * A friendly, developer-facing message from the AI explaining what was found
     * in the existing codebase and what it suggests. Shown to the user before committing.
     */
    private String suggestion;

    @Data
    public static class TaskDto {
        private Long id;
        private String status;
        private String title;
        private String description;
        private String type;
        private Integer estimatedHours;
        private String priority;
    }
}
