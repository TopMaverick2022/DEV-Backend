package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityLogResponseDto {
    private Long id;
    private String username;
    private String projectName;
    private String action;
    private String details;
    private LocalDateTime timestamp;
}
