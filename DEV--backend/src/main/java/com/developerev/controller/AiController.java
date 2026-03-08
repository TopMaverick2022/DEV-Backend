package com.developerev.controller;

import com.developerev.dto.SprintDetailDto;
import com.developerev.dto.TaskDependencyDto;
import com.developerev.service.AntiGravityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AntiGravityService antiGravityService;

    /**
     * Generate AI sprints for the given feature.
     *
     * POST /ai/generate-sprints/{featureId}
     *
     * Pre-condition: the feature must already have tasks (run /ai/generate-plan
     * first).
     * Returns a list of sprints, each with its assigned tasks.
     */
    @PostMapping("/generate-sprints/{featureId}")
    public ResponseEntity<List<SprintDetailDto>> generateSprints(@PathVariable Long featureId) {
        try {
            List<SprintDetailDto> sprints = antiGravityService.generateSprints(featureId);
            return ResponseEntity.ok(sprints);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Detect AI task dependencies for the given feature.
     *
     * POST /ai/detect-dependencies/{featureId}
     *
     * Pre-condition: the feature must already have tasks (run /ai/generate-plan
     * first).
     * Returns a list of dependency pairs showing which tasks must be completed
     * before others can begin. Dependencies are persisted in the database.
     */
    @PostMapping("/detect-dependencies/{featureId}")
    public ResponseEntity<List<TaskDependencyDto>> detectDependencies(@PathVariable Long featureId) {
        try {
            List<TaskDependencyDto> dependencies = antiGravityService.detectDependencies(featureId);
            return ResponseEntity.ok(dependencies);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
