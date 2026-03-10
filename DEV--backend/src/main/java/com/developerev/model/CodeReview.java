package com.developerev.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "code_reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fileId;

    /** Detected language for this file (e.g. "Python", "Java"). */
    private String language;

    /**
     * JSON array string of issues detected by AI.
     * Example: [{"line":12,"type":"Bug","message":"Possible null pointer"}]
     */
    @Column(columnDefinition = "TEXT")
    private String issues;

    /**
     * JSON array string of improvement suggestions from AI.
     * Example: [{"line":25,"message":"Use dependency injection"}]
     */
    @Column(columnDefinition = "TEXT")
    private String suggestions;

    /**
     * JSON array string of architecture insights detected by AI.
     * Example: ["Mixed concerns: business logic in controller"]
     */
    @Column(columnDefinition = "TEXT")
    private String architectureInsights;

    /** Number of Bug-type issues found in this file. */
    @Builder.Default
    private int bugCount = 0;

    /** Number of Security-type issues found in this file. */
    @Builder.Default
    private int securityCount = 0;

    /** Number of Performance-type issues found in this file. */
    @Builder.Default
    private int performanceCount = 0;
}
