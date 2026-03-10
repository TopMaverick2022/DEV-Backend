package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Full result returned by GET /ai/code-review/{projectId} once the
 * async pipeline completes (status = DONE).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectReviewResponseDto {

    private Long projectId;
    private String projectName;

    /** PENDING | PROCESSING | DONE | FAILED */
    private String status;

    private int totalFilesReviewed;

    // ─── Aggregate Counts ────────────────────────────────────────────────────
    private int totalBugs;
    private int totalSecurity;
    private int totalPerformance;
    private int totalArchitectureIssues;

    // ─── Per-file Reviews ────────────────────────────────────────────────────
    private List<FileReviewDto> fileReviews;

    // ─── Project-level Issues ────────────────────────────────────────────────
    /** Descriptions of architecture violations detected across the project. */
    private List<String> architectureIssues;

    /** Descriptions of security issues detected across the project. */
    private List<String> securityIssues;

    /** Human-readable AI summary of the overall project quality. */
    private String summary;
}
