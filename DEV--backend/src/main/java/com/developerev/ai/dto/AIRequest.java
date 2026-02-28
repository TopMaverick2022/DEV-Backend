package com.developerev.ai.dto;

import lombok.Data;

@Data
public class AIRequest {
    private String userContext; // The code, log, or github commit diff
    private String preferredProvider; // e.g., "GEMINI"
}
