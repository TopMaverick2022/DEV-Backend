package com.developerev.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "code_projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Original zip filename, e.g. "springboot-project.zip". */
    private String name;

    /**
     * Processing status: PENDING → PROCESSING → DONE | FAILED.
     * Used by the async review pipeline.
     */
    @Builder.Default
    private String status = "PENDING";

    /** Number of source files that were analysed by AI. */
    @Builder.Default
    private int filesAnalyzed = 0;

    private LocalDateTime uploadedAt;

    @PrePersist
    public void prePersist() {
        this.uploadedAt = LocalDateTime.now();
        if (this.status == null)
            this.status = "PENDING";
    }
}
