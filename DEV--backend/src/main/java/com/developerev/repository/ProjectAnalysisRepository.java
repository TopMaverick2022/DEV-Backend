package com.developerev.repository;

import com.developerev.model.ProjectAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectAnalysisRepository extends JpaRepository<ProjectAnalysis, Long> {

    /** Look up the analysis result for a given project. */
    Optional<ProjectAnalysis> findByProjectId(Long projectId);
}
