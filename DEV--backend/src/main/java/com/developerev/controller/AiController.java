package com.developerev.controller;

import com.developerev.dto.ArchitectureResponseDto;
import com.developerev.dto.ProjectReviewResponseDto;
import com.developerev.dto.SprintDetailDto;
import com.developerev.dto.TaskDependencyDto;
import com.developerev.dto.AiAnalysisRequest;
import com.developerev.dto.CodeRequest;
import com.developerev.dto.DebugRequest;
import com.developerev.service.AntiGravityService;
import com.developerev.service.CodeReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

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

    /**
     * POST /ai/analyze
     * Unified endpoint for multiple types of code analysis:
     * explain, debug, performance, architecture, refactor, edge-case, complexity
     * Supports either a single JSON object or a JSON array of objects.
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeCode(@RequestBody JsonNode requestNode) {
        try {
            if (requestNode.isArray()) {
                // Return a list of responses
                List<String> results = new java.util.ArrayList<>();
                for (JsonNode node : requestNode) {
                    AiAnalysisRequest request = objectMapper.treeToValue(node, AiAnalysisRequest.class);
                    if (request.getAnalysisType() == null || request.getAnalysisType().isBlank()) {
                        request.setAnalysisType("all");
                    }
                    results.add(antiGravityService.analyzeCode(request));
                }
                return ResponseEntity.ok(results);
            } else if (requestNode.isObject()) {
                // Return a single response string to maintain backward capability
                AiAnalysisRequest request = objectMapper.treeToValue(requestNode, AiAnalysisRequest.class);
                if (request.getAnalysisType() == null || request.getAnalysisType().isBlank()) {
                    request.setAnalysisType("all");
                }
                return ResponseEntity.ok(antiGravityService.analyzeCode(request));
            } else {
                return ResponseEntity.badRequest().body("Invalid JSON payload format");
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error during analysis: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Individual AI Feature Endpoints
    // ─────────────────────────────────────────────────────────────────────────────

    @PostMapping("/debug")
    public ResponseEntity<?> debugError(@RequestBody DebugRequest request) {
        AiAnalysisRequest analysisRequest = new AiAnalysisRequest();
        analysisRequest.setAnalysisType("debug");
        analysisRequest.setErrorLog(request.getErrorLog());
        return ResponseEntity.ok(antiGravityService.analyzeCode(analysisRequest));
    }

    @PostMapping("/refactor")
    public ResponseEntity<?> refactor(@RequestBody CodeRequest request) {
        AiAnalysisRequest analysisRequest = new AiAnalysisRequest();
        analysisRequest.setAnalysisType("refactor");
        analysisRequest.setCode(request.getCode());
        return ResponseEntity.ok(antiGravityService.analyzeCode(analysisRequest));
    }

    @PostMapping("/security-scan")
    public ResponseEntity<?> securityScan(@RequestBody CodeRequest request) {
        AiAnalysisRequest analysisRequest = new AiAnalysisRequest();
        analysisRequest.setAnalysisType("security-scan");
        analysisRequest.setCode(request.getCode());
        return ResponseEntity.ok(antiGravityService.analyzeCode(analysisRequest));
    }

    @PostMapping("/performance")
    public ResponseEntity<?> performanceCheck(@RequestBody CodeRequest request) {
        AiAnalysisRequest analysisRequest = new AiAnalysisRequest();
        analysisRequest.setAnalysisType("performance");
        analysisRequest.setCode(request.getCode());
        return ResponseEntity.ok(antiGravityService.analyzeCode(analysisRequest));
    }

    @PostMapping(value = "/dependency-graph", consumes = "multipart/form-data")
    public ResponseEntity<?> dependencyGraph(@RequestParam("file") MultipartFile zip) {
        try {
            String result = antiGravityService.generateDependencyGraph(zip);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error generating dependency graph: " + e.getMessage());
        }
    }

    @PostMapping("/test-generator")
    public ResponseEntity<?> generateTests(@RequestBody CodeRequest request) {
        AiAnalysisRequest analysisRequest = new AiAnalysisRequest();
        analysisRequest.setAnalysisType("test-generator");
        analysisRequest.setCode(request.getCode());
        return ResponseEntity.ok(antiGravityService.analyzeCode(analysisRequest));
    }

    @PostMapping("/generate-docs")
    public ResponseEntity<?> generateDocs(@RequestBody CodeRequest request) {
        AiAnalysisRequest analysisRequest = new AiAnalysisRequest();
        analysisRequest.setAnalysisType("generate-docs");
        analysisRequest.setCode(request.getCode());
        return ResponseEntity.ok(antiGravityService.analyzeCode(analysisRequest));
    }

}
