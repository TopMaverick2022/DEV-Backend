package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogAnalysisResponseDto {
    private List<Issue> issues;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue {
        private String type;
        private String problem;
        private String severity;
        private String explanation;
        private String solution;
    }
}
