package com.developerev.controller;

import com.developerev.dto.ArchitectureResponseDto;
import com.developerev.dto.CriticalPathResponseDto;
import com.developerev.dto.SprintDetailDto;
import com.developerev.dto.TaskDependencyDto;
import com.developerev.service.AntiGravityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AntiGravityService antiGravityService;

    /**
     * POST /ai/generate-sprints/{featureId}
     *
     * Pre-condition: feature must have tasks (run /ai/project-plan first).
     * Returns sprints with assigned tasks.
     */
    @PostMapping("/generate-sprints/{featureId}")
    public ResponseEntity<List<SprintDetailDto>> generateSprints(@PathVariable Long featureId) {
        try {
            return ResponseEntity.ok(antiGravityService.generateSprints(featureId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * POST /ai/detect-dependencies/{featureId}
     *
     * Pre-condition: feature must have tasks (run /ai/project-plan first).
     * Detects and persists logical task dependencies, returns dependency list.
     */
    @PostMapping("/detect-dependencies/{featureId}")
    public ResponseEntity<List<TaskDependencyDto>> detectDependencies(@PathVariable Long featureId) {
        try {
            return ResponseEntity.ok(antiGravityService.detectDependencies(featureId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * POST /ai/generate-architecture
     *
     * Body: { "idea": "Build a payment system" }
     *
     * Generates a full system architecture blueprint (services, APIs, events)
     * and persists it in the database. Returns the structured design plus a planId.
     */
    @PostMapping("/generate-architecture")
    public ResponseEntity<ArchitectureResponseDto> generateArchitecture(
            @RequestBody Map<String, String> body) {
        String idea = body.get("idea");
        if (idea == null || idea.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(antiGravityService.generateArchitecture(idea));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
