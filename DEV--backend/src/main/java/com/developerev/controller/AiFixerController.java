package com.developerev.controller;

import com.developerev.service.AiFixerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Code Fixer REST Controller.
 *
 * GET  /api/fixer/{projectId}/issues       — All aggregated issues per file
 * POST /api/fixer/{projectId}/fix-file     — Fix all issues in one file
 * POST /api/fixer/{projectId}/fix-all      — Fix all files in the project
 */
@Slf4j
@RestController
@RequestMapping("/api/fixer")
@RequiredArgsConstructor
public class AiFixerController {

    private final AiFixerService aiFixerService;

    // ── Get all issues aggregated across all files ─────────────────────────

    @GetMapping("/{projectId}/issues")
    public ResponseEntity<List<Map<String, Object>>> getAllIssues(@PathVariable Long projectId) {
        log.info("Fetching all issues for project {}", projectId);
        return ResponseEntity.ok(aiFixerService.getAllIssues(projectId));
    }

    // ── Fix a single file ─────────────────────────────────────────────────

    @PostMapping("/{projectId}/fix-file")
    public ResponseEntity<Map<String, Object>> fixFile(
            @PathVariable Long projectId,
            @RequestBody Map<String, String> payload) {
        String filePath = payload.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filePath is required"));
        }
        log.info("AI Fix requested for project={} file={}", projectId, filePath);
        try {
            Map<String, Object> result = aiFixerService.fixFile(projectId, filePath);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Fix failed for project={} file={}: {}", projectId, filePath, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Fix all files in a project ────────────────────────────────────────

    @PostMapping("/{projectId}/fix-all")
    public ResponseEntity<List<Map<String, Object>>> fixAll(
            @PathVariable Long projectId,
            @RequestBody Map<String, List<String>> payload) {
        List<String> filePaths = payload.get("filePaths");
        if (filePaths == null || filePaths.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("AI Fix-All requested for project={} ({} files)", projectId, filePaths.size());
        try {
            List<Map<String, Object>> results = aiFixerService.fixAllFiles(projectId, filePaths);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Fix-All failed for project={}: {}", projectId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
