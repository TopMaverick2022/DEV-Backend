package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecommendedDependencyDto {
    private String name;
    private String description;
    private boolean checked;
}
