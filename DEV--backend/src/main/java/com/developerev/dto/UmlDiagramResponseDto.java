package com.developerev.dto;

import com.developerev.model.UmlDiagram;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UmlDiagramResponseDto {
    private Long id;
    private Long projectId;
    private String name;
    private String type;
    private String mermaidCode;

    public UmlDiagramResponseDto(UmlDiagram diagram) {
        this.id = diagram.getId();
        this.projectId = diagram.getProjectId();
        this.name = diagram.getName();
        this.type = diagram.getType();
        this.mermaidCode = diagram.getMermaidCode();
    }
}
