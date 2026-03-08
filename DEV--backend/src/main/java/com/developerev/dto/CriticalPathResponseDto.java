package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for GET /features/{featureId}/critical-path
 *
 * Example:
 * {
 * "criticalPathHours": 36,
 * "tasks": ["Database Schema", "Backend API", "Frontend UI"]
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CriticalPathResponseDto {

    /** Total hours along the longest dependency chain (the critical path). */
    private int criticalPathHours;

    /**
     * Ordered list of task titles forming the critical path, from start to finish.
     */
    private List<String> tasks;
}
