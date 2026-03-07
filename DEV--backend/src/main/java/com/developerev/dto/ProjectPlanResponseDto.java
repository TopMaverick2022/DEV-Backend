package com.developerev.dto;

import lombok.Data;
import java.util.List;

@Data
public class ProjectPlanResponseDto {
    private String featureName;
    private String complexity;
    private Integer totalEstimatedHours;
    private List<TaskDto> tasks;

    @Data
    public static class TaskDto {
        private String title;
        private String description;
        private String type;
        private Integer estimatedHours;
        private String priority;
    }
}
