package com.developerev.model;

import com.developerev.entity.User;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Project {

    /**
     * STANDALONE = single embedded project (HTML+CSS+JS+backend in one repo, default)
     * FRONTEND   = a frontend-only project that pairs with a separate backend project
     * BACKEND    = a backend-only project that pairs with a separate frontend project
     */
    @Column(name = "project_type", nullable = false)
    private String projectType = "STANDALONE";

    /**
     * ID of the linked companion project (null for STANDALONE).
     * Bidirectional: both sides store each other's ID.
     */
    @Column(name = "related_project_id")
    private Long relatedProjectId;

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

    @Column(name = "ai_business_context", columnDefinition = "TEXT")
    private String aiBusinessContext;

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
