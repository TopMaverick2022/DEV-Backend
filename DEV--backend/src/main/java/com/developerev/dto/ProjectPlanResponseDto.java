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
