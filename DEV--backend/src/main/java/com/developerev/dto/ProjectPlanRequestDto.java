package com.developerev.dto;

import lombok.Data;

@Data
public class ProjectPlanRequestDto {
    private Long projectId;
    private String featureDescription;
}
