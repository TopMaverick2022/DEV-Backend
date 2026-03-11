package com.developerev.dto;

import lombok.Data;

@Data
public class AiAnalysisRequest {
    private String analysisType;
    private String code;
    private String errorLog; // Specifically used for the debug endpoint if they pass it exactly
}
