package com.developerev.controller;

import com.developerev.dto.ArchitectureResponseDto;
import com.developerev.dto.ProjectReviewResponseDto;
import com.developerev.dto.SprintDetailDto;
import com.developerev.dto.TaskDependencyDto;
import com.developerev.service.AntiGravityService;
import com.developerev.service.CodeReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AntiGravityService antiGravityService;
    private final CodeReviewService codeReviewService;

    /**
     * POST /ai/generate-sprints/{featureId}
     * Pre-condition: feature must have tasks (run /ai/project-plan first).
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
     * Pre-condition: feature must have tasks (run /ai/project-plan first).
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
     * Body: { "idea": "Build a payment system" }
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

    /**
     * POST /ai/code-review
     *
     * Accepts a zip file containing a code project.
     * Extracts supported source files (.java, .js, .ts, .py, .go, .kt, ...),
     * sends each to Gemini for code review, persists results, and returns a
     * structured review report.
     *
     * Postman: Body → form-data → Key: "project", Type: File
     */
    @PostMapping(value = "/code-review", consumes = "multipart/form-data")
    public ResponseEntity<ProjectReviewResponseDto> reviewCode(
            @RequestParam("project") MultipartFile zipFile) {
        if (zipFile.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            ProjectReviewResponseDto result = codeReviewService.reviewProject(zipFile);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
