package com.developerev.controller;

import com.developerev.model.ProjectHealth;
import com.developerev.service.HealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProjectHealthController {

    private final HealthService healthService;

    @GetMapping("/projects/{id}/health")
    public ResponseEntity<?> getProjectHealth(@PathVariable Long id) {
        ProjectHealth health = healthService.calculateHealth(id);
        return ResponseEntity.ok(health);
    }
}
