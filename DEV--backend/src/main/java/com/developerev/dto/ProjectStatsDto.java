package com.developerev.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectStatsDto {
    private double healthScore;
    private int totalFilesAnalyzed;
    private int totalBugs;
    private int totalSecurityIssues;
    private int totalPerformanceIssues;
    private int totalCodeQualityIssues;
    private String techDebtEstimate;
    private String syncStatus; // "SYNCED", "OUT_OF_SYNC", "UNKNOWN"
}
