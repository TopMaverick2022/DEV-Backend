package com.developerev.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "uml_diagrams")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UmlDiagram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    @Column(columnDefinition = "TEXT")
    private String name;

    private String type; // e.g. "Sequence", "Class", "State", "Entity-Relationship"

    @Column(columnDefinition = "TEXT")
    private String mermaidCode;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
