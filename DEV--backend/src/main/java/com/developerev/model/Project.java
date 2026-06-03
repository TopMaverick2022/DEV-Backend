package com.developerev.model;

import com.developerev.entity.User;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    private String githubRepoUrl;

    private String language;

    @Column(name = "language_version")
    private String languageVersion;

    private String framework;

    @Column(name = "framework_version")
    private String frameworkVersion;

    @Column(name = "database_name")
    private String databaseName;

    @Column(name = "database_version")
    private String databaseVersion;

    @Column(columnDefinition = "TEXT")
    private String dependencies;

    @Column(name = "last_analyzed_commit")
    private String lastAnalyzedCommit;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password", "passwordHistory", "role", "provider", "providerId", "verified", "createdAt"})
    private User owner;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
