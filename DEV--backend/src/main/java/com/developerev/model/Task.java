package com.developerev.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private Long featureId;

    private Long sprintId; // set after AI sprint generation, null until then

    private String title;

    @Column(length = 2000)
    private String description;

    private String type;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    private Integer estimatedHours;

    private String priority;

    private String assignee; // developer assigned to this task (e.g. "Arun", "Priya")

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
