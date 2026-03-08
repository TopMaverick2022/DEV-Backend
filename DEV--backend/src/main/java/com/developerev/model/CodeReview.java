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
}
