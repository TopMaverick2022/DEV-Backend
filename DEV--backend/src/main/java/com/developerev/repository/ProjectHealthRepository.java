package com.developerev.repository;

import com.developerev.model.ProjectHealth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectHealthRepository extends JpaRepository<ProjectHealth, Long> {
    Optional<ProjectHealth> findTopByProjectIdOrderByCreatedAtDesc(Long projectId);
}
