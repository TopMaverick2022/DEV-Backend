package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeRequestDto {
    private String projectFrameworks;
    private String architecturePattern;
    private String codingConventions;
}
