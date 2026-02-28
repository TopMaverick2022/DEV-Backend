package com.developerev.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderResponse {
    private String content;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private String providerName;
}
