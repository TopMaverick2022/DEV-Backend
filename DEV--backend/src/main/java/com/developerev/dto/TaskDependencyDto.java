package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Clean API response describing a single task dependency.
 *
 * Example:
 * {
 * "dependentTaskId": 3,
 * "dependentTaskTitle": "Frontend Login UI",
 * "prerequisiteTaskId": 1,
 * "prerequisiteTaskTitle": "Database Schema"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDependencyDto {

    private Long dependentTaskId;
    private String dependentTaskTitle;

    private Long prerequisiteTaskId;
    private String prerequisiteTaskTitle;
}
