package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Immediate response for POST /ai/code-review (async).
 * Client uses projectId to poll GET /ai/code-review/{projectId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewSubmitResponseDto {

    private Long projectId;

    /** PENDING, PROCESSING, DONE, or FAILED */
    private String status;

    private String message;
}
