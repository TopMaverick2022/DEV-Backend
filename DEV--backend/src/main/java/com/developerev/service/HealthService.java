package com.developerev.service;

import com.developerev.model.ProjectHealth;
import com.developerev.repository.ProjectHealthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class HealthService {
    
    private final ProjectHealthRepository projectHealthRepository;

    public ProjectHealth calculateHealth(Long projectId) {
        log.info("Calculating health for project {}", projectId);
        
        // This is a placeholder algorithm for the dashboard
        // Eventually this will query CodeReview data and aggregate bug Count, security count etc.
        java.util.Optional<ProjectHealth> existing = projectHealthRepository.findTopByProjectIdOrderByCreatedAtDesc(projectId);
        if (existing.isPresent()) {
            return existing.get();
        }

        ProjectHealth health = ProjectHealth.builder()
                .projectId(projectId)
                .codeQualityScore(82)
                .securityScore(75)
                .performanceScore(88)
                .technicalDebt("Medium")
                .build();
        return projectHealthRepository.save(health);
    }
}
