package com.developerev.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "code_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    /** e.g. "TaskController.java" */
    private String filename;

    /**
     * Relative path inside the zip, e.g.
     * "src/main/java/com/example/TaskController.java"
     */
    @Column(length = 1000)
    private String path;
}
