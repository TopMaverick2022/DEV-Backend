package com.developerev.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewResponse {
    private boolean success;
    private String data;
    private String error;
    private String providerUsed;
    private LocalDateTime timestamp;
}
