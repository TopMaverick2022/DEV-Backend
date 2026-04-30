package com.developerev.controller;

import com.developerev.dto.ArchitectureResponseDto;
import com.developerev.dto.ProjectReviewResponseDto;
import com.developerev.dto.SprintDetailDto;
import com.developerev.dto.TaskDependencyDto;
import com.developerev.dto.DatabaseIntelligenceRequestDto;
import com.developerev.dto.DatabaseSchemaResponseDto;
import com.developerev.dto.AiAnalysisRequest;
import com.developerev.dto.SystemQueryRequestDto;
import com.developerev.dto.SystemQueryResponseDto;
import com.developerev.dto.KnowledgeRequestDto;
import com.developerev.dto.KnowledgeResponseDto;
import com.developerev.dto.CodeRequest;
import com.developerev.service.AntiGravityService;
import com.developerev.service.CodeReviewService;
import com.developerev.service.LogAnalysisService;
import com.developerev.service.KnowledgeService;
import com.developerev.service.SystemIntelligenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AntiGravityService antiGravityService;
    private final CodeReviewService codeReviewService;
    private final LogAnalysisService logAnalysisService;
    private final KnowledgeService knowledgeService;
    private final SystemIntelligenceService systemIntelligenceService;
    private final ObjectMapper objectMapper;
    private final com.developerev.service.GitService gitService;
    private final com.developerev.repository.ProjectRepository projectRepository;
    private final com.developerev.service.GeminiClient geminiClient;


    /**
     * POST /ai/generate-sprints/{featureId}
     * Pre-condition: feature must have tasks (run /ai/project-plan first).
     */
    @PostMapping("/generate-sprints/{featureId}")
    public ResponseEntity<List<SprintDetailDto>> generateSprints(@PathVariable("featureId") Long featureId) {
        return ResponseEntity.ok(antiGravityService.generateSprints(featureId));
    }

    /**
     * POST /ai/detect-dependencies/{featureId}
     * Pre-condition: feature must have tasks (run /ai/project-plan first).
     */
    @PostMapping("/detect-dependencies/{featureId}")
    public ResponseEntity<List<TaskDependencyDto>> detectDependencies(@PathVariable("featureId") Long featureId) {
        return ResponseEntity.ok(antiGravityService.detectDependencies(featureId));
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
            throw new IllegalArgumentException("Idea description is required");
        }
        return ResponseEntity.ok(antiGravityService.generateArchitecture(idea));
    }

    /**
     * POST /ai/generate-database-schema
     * Generate database schemas from a feature description.
     */
    @PostMapping("/generate-database-schema")
    public ResponseEntity<DatabaseSchemaResponseDto> generateDatabaseSchema(
            @RequestBody DatabaseIntelligenceRequestDto body) {
        String idea = body.getFeatureDescription();
        if (idea == null || idea.isBlank()) {
            throw new IllegalArgumentException("Feature description is required");
        }
        return ResponseEntity.ok(antiGravityService.generateDatabaseSchema(idea));
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
    @PostMapping(value = "/code-review-zip", consumes = "multipart/form-data")
    public ResponseEntity<ProjectReviewResponseDto> reviewCode(
            @RequestParam("project") MultipartFile zipFile,
            @RequestParam(value = "projectId", required = false) Long projectId,
            org.springframework.security.core.Authentication authentication) throws Exception {
        if (zipFile.isEmpty()) {
            throw new IllegalArgumentException("Project zip file is empty");
        }
        String username = authentication != null ? authentication.getName() : null;
        ProjectReviewResponseDto result = codeReviewService.reviewProject(zipFile, username, projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /ai/analyze-workspace/{projectId}
     *
     * Analyzes a project that has already been cloned to the server via GitService.
     * No zip upload needed.
     */
    @PostMapping("/analyze-workspace/{projectId}")
    public ResponseEntity<ProjectReviewResponseDto> analyzeWorkspace(
            @PathVariable("projectId") Long projectId,
            @RequestParam(value = "projectName", required = false, defaultValue = "Workspace") String projectName) throws Exception {
        java.nio.file.Path workspaceDir = gitService.getRepoDir(projectId).toPath();
        if (!java.nio.file.Files.exists(workspaceDir)) {
            throw new jakarta.persistence.EntityNotFoundException("Workspace not found for project " + projectId);
        }
        ProjectReviewResponseDto result = codeReviewService.reviewWorkspace(workspaceDir, projectName, projectId);

        // Save the last analyzed commit SHA
        projectRepository.findById(projectId).ifPresent(p -> {
            String latestCommit = gitService.getLatestCommitSha(
                    p.getGithubRepoUrl() != null ? p.getGithubRepoUrl() : "", projectId);
            if (latestCommit != null) {
                p.setLastAnalyzedCommit(latestCommit);
                projectRepository.save(p);
            }
        });

        return ResponseEntity.ok(result);
    }

    /**
     * POST /ai/analyze
     * Unified endpoint for multiple types of code analysis:
     * explain, debug, performance, architecture, refactor, edge-case, complexity
     * Supports either a single JSON object or a JSON array of objects.
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeCode(@RequestBody JsonNode requestNode) throws Exception {
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
            throw new IllegalArgumentException("Invalid JSON payload format");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Individual AI Feature Endpoints
    // ─────────────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/analyze-logs", consumes = "multipart/form-data")
    public ResponseEntity<?> analyzeLogs(@RequestParam("file") MultipartFile logFile) throws Exception {
        return ResponseEntity.ok(logAnalysisService.analyzeLogFile(logFile));
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
    public ResponseEntity<?> dependencyGraph(@RequestParam("file") MultipartFile zip) throws Exception {
        String result = antiGravityService.generateDependencyGraph(zip);
        return ResponseEntity.ok(result);
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

    @PostMapping("/knowledge")
    public ResponseEntity<KnowledgeResponseDto> saveKnowledge(@RequestBody KnowledgeRequestDto request) {
        return ResponseEntity.ok(knowledgeService.saveKnowledge(request));
    }

    @GetMapping("/knowledge")
    public ResponseEntity<KnowledgeResponseDto> getKnowledge() {
        return ResponseEntity.ok(knowledgeService.getKnowledge());
    }

    @PostMapping(value = "/system-query", consumes = "multipart/form-data")
    public ResponseEntity<SystemQueryResponseDto> answerSystemQuery(
            @RequestParam("project") MultipartFile zipFile,
            @RequestParam("query") String query) throws Exception {
        if (zipFile.isEmpty() || query == null || query.isBlank()) {
            throw new IllegalArgumentException("Project zip file and query are required");
        }
        return ResponseEntity.ok(systemIntelligenceService.answerSystemQuery(zipFile, query));
    }

}
