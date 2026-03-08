package com.developerev.dto;

import com.developerev.model.Task;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class FeatureDetailResponseDto {
    private Long id;
    private String featureName;
    private String complexity;
    private Integer totalEstimatedHours;
    private List<Task> tasks;
}
