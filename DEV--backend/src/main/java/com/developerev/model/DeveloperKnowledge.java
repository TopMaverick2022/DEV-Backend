package com.developerev.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "developer_knowledge")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeveloperKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String projectFrameworks;

    @Column(columnDefinition = "TEXT")
    private String architecturePattern;

    @Column(columnDefinition = "TEXT")
    private String codingConventions;

}
