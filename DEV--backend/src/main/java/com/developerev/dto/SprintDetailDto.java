package com.developerev.dto;

import com.developerev.model.Task;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Public API response DTO for a sprint with its assigned tasks.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SprintDetailDto {

    private Long sprintId;
    private String name;
    private int sprintNumber;
    private List<Task> tasks;
}
