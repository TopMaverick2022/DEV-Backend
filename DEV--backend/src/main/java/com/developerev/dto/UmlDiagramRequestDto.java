package com.developerev.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UmlDiagramRequestDto {
    private Long projectId;
    private String name;
    private String type;
    private String context;
}
