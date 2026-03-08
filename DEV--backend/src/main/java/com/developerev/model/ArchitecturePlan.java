package com.developerev.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "architecture_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchitecturePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The original project idea submitted by the user. */
    private String projectIdea;

    /** Raw JSON string of the full AI-generated architecture blueprint. */
    @Column(columnDefinition = "TEXT")
    private String architectureJson;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
