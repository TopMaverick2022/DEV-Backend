package com.developerev.service;

import com.developerev.dto.FileReviewDto;
import com.developerev.dto.ProjectReviewResponseDto;
import com.developerev.model.CodeFile;
import com.developerev.model.CodeProject;
import com.developerev.model.CodeReview;
import com.developerev.repository.CodeFileRepository;
import com.developerev.repository.CodeProjectRepository;
import com.developerev.repository.CodeReviewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Orchestrates the full AI code review pipeline:
 *
 * ZipExtractorService → extracts ZIP to temp dir
 * ↓
 * DirectoryScannerService → lists all source files
 * ↓
 * LanguageDetectionService → detects language per file
 * ↓
 * FileContentService → reads file content (truncated)
 * ↓
 * AiPromptBuilder → builds language-aware prompt
 * ↓
 * GeminiClient → calls Gemini AI
 * ↓
 * DB persistence → saves project, files, reviews
 * ↓
 * ProjectReviewResponseDto → structured JSON response
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewService {

    // ── Pipeline services ──────────────────────────────────────────────────
    private final ZipExtractorService zipExtractorService;
    private final DirectoryScannerService directoryScannerService;
    private final LanguageDetectionService languageDetectionService;
    private final FileContentService fileContentService;
    private final AiPromptBuilder aiPromptBuilder;
    private final GeminiClient geminiClient;

    // ── Persistence ────────────────────────────────────────────────────────
    private final CodeProjectRepository codeProjectRepository;
    private final CodeFileRepository codeFileRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    public ProjectReviewResponseDto reviewProject(MultipartFile zipFile, String username, Long linkedProjectId) throws IOException {

        log.info("ZIP received: {} from user: {}", zipFile.getOriginalFilename(), username);

        // Use provided projectId if given, otherwise auto-create one
        if (linkedProjectId == null && username != null && !username.isBlank()) {
            try {
                com.developerev.model.Project masterProject = new com.developerev.model.Project();
                masterProject.setName(zipFile.getOriginalFilename());
                masterProject.setDescription("ZIP Upload");
                com.developerev.model.Project savedMaster = projectService.createProject(username, masterProject);
                linkedProjectId = savedMaster.getId();
                log.info("Created master Project id={} for ZIP upload", linkedProjectId);
            } catch (Exception e) {
                log.warn("Failed to create master Project for ZIP upload: {}", e.getMessage());
            }
        } else if (linkedProjectId != null) {
            log.info("Linking ZIP review to existing Project id={}", linkedProjectId);
        }

        // ── Stage 1: Persist AI project record ────────────────────────────
        CodeProject project = codeProjectRepository.save(
                CodeProject.builder()
                        .name(zipFile.getOriginalFilename())
                        .linkedProjectId(linkedProjectId)
                        .build());
        log.info("Created CodeProject id={} name={}", project.getId(), project.getName());

        // ── Stage 2: Extract ZIP to workspace directly ────────────────────
        Long targetId = linkedProjectId != null ? linkedProjectId : project.getId();
        Path workspaceDir = java.nio.file.Paths.get("workspaces", "project_" + targetId).toAbsolutePath().normalize();
        
        zipExtractorService.extractTo(zipFile, workspaceDir);

        log.info("Code upload complete and stored in workspace: {}", workspaceDir);

        return ProjectReviewResponseDto.builder()
                .projectId(project.getId())
                .projectName(project.getName())
                .totalFilesReviewed(0)
                .fileReviews(new ArrayList<>())
                .status("UPLOADED")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Workspace entry point (for JGit clones, NO ZIP INVOLVED)
    // ─────────────────────────────────────────────────────────────────────────

    public ProjectReviewResponseDto reviewWorkspace(Path workspaceDir, String projectName, Long linkedProjectId) throws IOException {
        log.info("Analyzing workspace directory: {}", workspaceDir.toString());

        CodeProject project = codeProjectRepository.save(
                CodeProject.builder()
                        .name(projectName)
                        .linkedProjectId(linkedProjectId)
                        .build());
        log.info("Created CodeProject id={} name={}", project.getId(), project.getName());

        List<Path> sourceFiles = directoryScannerService.scan(workspaceDir);
        
        List<FileReviewDto> fileReviews = processFilesInBatches(sourceFiles, project, workspaceDir, false, null);

        log.info("Workspace code review complete: {} files reviewed for project id={}",
                fileReviews.size(), project.getId());

        return ProjectReviewResponseDto.builder()
                .projectId(project.getId())
                .projectName(project.getName())
                .totalFilesReviewed(fileReviews.size())
                .fileReviews(fileReviews)
                .status("DONE")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Chunks files, extracts contents, creates CodeFile entities, calls Gemini once per batch,
     * and persists reviews, returning the combined FileReviewDto results.
     *
     * @param progressCallback optional SSE callback: receives "current/total:filename" events
     */
    public List<FileReviewDto> processFilesInBatches(
            List<Path> sourceFiles, CodeProject project, Path rootDir,
            boolean isIncremental, Consumer<String> progressCallback) {
        List<FileReviewDto> allReviews = new ArrayList<>();
        
        // 1) Respect free-tier Gemini (15 RPM) — prevents quota exhaustion
        if (!isIncremental && sourceFiles.size() > 100) {
            log.warn("Project has {} files. Limiting initial analysis to first 100 files to protect free-tier quota.", sourceFiles.size());
            sourceFiles = sourceFiles.subList(0, 100);
        }
        int totalFiles = sourceFiles.size();
        int processedFiles = 0;

        List<Path> pendingFiles = new ArrayList<>();
        List<String> pendingFilenames = new ArrayList<>();
        List<String> pendingLanguages = new ArrayList<>();
        List<String> pendingContents = new ArrayList<>();
        List<CodeFile> pendingCodeFiles = new ArrayList<>();

        for (Path file : sourceFiles) {
            String language = languageDetectionService.detectLanguage(file);
            String filename = file.getFileName().toString();
            String relativePath = rootDir.relativize(file).toString().replace("\\", "/");
            String content = fileContentService.readFile(file);
            
            if (content.isBlank()) {
                processedFiles++;
                if (progressCallback != null) {
                    progressCallback.accept(processedFiles + "/" + totalFiles + ":" + filename);
                }
                continue;
            }

            String fileHash = DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8));
            Optional<CodeFile> cachedFileOpt = codeFileRepository.findFirstByFileHash(fileHash);

            if (cachedFileOpt.isPresent()) {
                // CACHE HIT
                CodeFile cachedFile = cachedFileOpt.get();
                List<CodeReview> cachedReviews = codeReviewRepository.findByFileId(cachedFile.getId());
                
                CodeFile codeFile = codeFileRepository.save(
                        CodeFile.builder()
                                .projectId(project.getId())
                                .filename(filename)
                                .path(relativePath)
                                .fileHash(fileHash)
                                .language(language)
                                .lineCount(content.split("\n").length)
                                .build());

                if (!cachedReviews.isEmpty()) {
                    CodeReview cachedReview = cachedReviews.get(0);
                    codeReviewRepository.save(
                        CodeReview.builder()
                                .fileId(codeFile.getId())
                                .language(language)
                                .issues(cachedReview.getIssues())
                                .suggestions(cachedReview.getSuggestions())
                                .architectureInsights(cachedReview.getArchitectureInsights())
                                .bugCount(cachedReview.getBugCount())
                                .securityCount(cachedReview.getSecurityCount())
                                .performanceCount(cachedReview.getPerformanceCount())
                                .build());

                    FileReviewDto dto = new FileReviewDto();
                    dto.setFilename(filename);
                    dto.setPath(relativePath);
                    dto.setLanguage(language);
                    try {
                        if (cachedReview.getIssues() != null && !cachedReview.getIssues().equals("[]")) {
                            dto.setIssues(objectMapper.readValue(cachedReview.getIssues(), new TypeReference<List<FileReviewDto.IssueDto>>(){}));
                        } else dto.setIssues(new ArrayList<>());
                        
                        if (cachedReview.getSuggestions() != null && !cachedReview.getSuggestions().equals("[]")) {
                            dto.setSuggestions(objectMapper.readValue(cachedReview.getSuggestions(), new TypeReference<List<FileReviewDto.SuggestionDto>>(){}));
                        } else dto.setSuggestions(new ArrayList<>());
                        
                        if (cachedReview.getArchitectureInsights() != null && !cachedReview.getArchitectureInsights().equals("[]")) {
                            dto.setArchitectureInsights(objectMapper.readValue(cachedReview.getArchitectureInsights(), new TypeReference<List<String>>(){}));
                        } else dto.setArchitectureInsights(new ArrayList<>());
                    } catch (Exception e) {
                        log.warn("Failed to parse cached review: {}", e.getMessage());
                        dto.setIssues(new ArrayList<>());
                        dto.setSuggestions(new ArrayList<>());
                        dto.setArchitectureInsights(new ArrayList<>());
                    }
                    allReviews.add(dto);
                    log.info("CACHE HIT: Reusing analysis for file: {}", filename);
                }
                
                processedFiles++;
                if (progressCallback != null) {
                    progressCallback.accept(processedFiles + "/" + totalFiles + ":" + filename);
                }
            } else {
                // CACHE MISS
                CodeFile codeFile = codeFileRepository.save(
                        CodeFile.builder()
                                .projectId(project.getId())
                                .filename(filename)
                                .path(relativePath)
                                .fileHash(fileHash)
                                .language(language)
                                .lineCount(content.split("\n").length)
                                .build());

                pendingFiles.add(file);
                pendingFilenames.add(filename);
                pendingLanguages.add(language);
                pendingContents.add(content);
                pendingCodeFiles.add(codeFile);
            }
        }

        // Process pending files in chunks of 5
        int totalPending = pendingFiles.size();

        for (int i = 0; i < totalPending; i += 5) {
            int end = Math.min(i + 5, totalPending);
            
            List<Path> batchPaths = pendingFiles.subList(i, end);
            List<String> filenames = pendingFilenames.subList(i, end);
            List<String> languages = pendingLanguages.subList(i, end);
            List<String> contents = pendingContents.subList(i, end);
            List<CodeFile> savedCodeFiles = pendingCodeFiles.subList(i, end);

            for (String fn : filenames) {
                processedFiles++;
                if (progressCallback != null) {
                    progressCallback.accept(processedFiles + "/" + totalFiles + ":" + fn);
                }
            }

            log.info("Sending batch of {} files to AI (batch {}/{})",
                    filenames.size(), (i / 5) + 1, (int) Math.ceil(totalPending / 5.0));
            String prompt = aiPromptBuilder.buildBatchPrompt(filenames, languages, contents);

            try {
                List<FileReviewDto> batchReviews = callAiBatchAndParse(prompt, filenames, languages, rootDir, batchPaths);
                
                for (FileReviewDto review : batchReviews) {
                    CodeFile matchingCodeFile = savedCodeFiles.stream()
                            .filter(cf -> cf.getFilename().equals(review.getFilename()))
                            .findFirst().orElse(null);
                            
                    if (matchingCodeFile != null) {
                        persistReview(matchingCodeFile.getId(), review.getLanguage() != null ? review.getLanguage() : "Unknown", review);
                        allReviews.add(review);
                    }
                }
            } catch (com.developerev.ai.exception.GeminiApiException e) {
                log.error("Critical AI API failure during batch: {}", e.getMessage());
                throw e;
            } catch (Exception e) {
                log.warn("Non-critical batch processing failure: {}", e.getMessage());
            }

            if (i + 5 < totalPending) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return allReviews;
    }

    /**
     * Calls Gemini for a BATCH of files and parses the JSON ARRAY response.
     */
    private List<FileReviewDto> callAiBatchAndParse(String prompt, List<String> filenames, List<String> languages, Path rootDir, List<Path> batchPaths) {
        try {
            String geminiResponse = geminiClient.callGemini(prompt);

            JsonNode root = objectMapper.readTree(geminiResponse);
            String textContent = root.path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            String cleaned = textContent
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            List<FileReviewDto> dtos = objectMapper.readValue(cleaned, new com.fasterxml.jackson.core.type.TypeReference<List<FileReviewDto>>() {});
            
            // Enrich dtos with path and fallback language
            for (FileReviewDto dto : dtos) {
                int index = filenames.indexOf(dto.getFilename());
                if (index != -1) {
                    dto.setPath(rootDir.relativize(batchPaths.get(index)).toString().replace("\\", "/"));
                    if (dto.getLanguage() == null) {
                        dto.setLanguage(languages.get(index));
                    }
                }
            }
            return dtos;

        } catch (com.developerev.ai.exception.GeminiApiException e) {
            // Rethrow Gemini API exceptions (429, 500, etc.)
            throw e;
        } catch (Exception e) {
            log.warn("AI batch review parsing failed: {}", e.getMessage());
            // Return empty reviews for all files in this batch so they aren't totally lost
            List<FileReviewDto> fallbacks = new ArrayList<>();
            for (int i = 0; i < filenames.size(); i++) {
                FileReviewDto dto = new FileReviewDto();
                dto.setFilename(filenames.get(i));
                dto.setPath(rootDir.relativize(batchPaths.get(i)).toString().replace("\\", "/"));
                dto.setLanguage(languages.get(i));
                dto.setIssues(List.of());
                dto.setSuggestions(List.of());
                dto.setArchitectureInsights(List.of());
                fallbacks.add(dto);
            }
            return fallbacks;
        }
    }

    /** Counts issues by type and persists the CodeReview entity. */
    private void persistReview(Long fileId, String language, FileReviewDto review) {
        int bugs = 0, security = 0, performance = 0;
        if (review.getIssues() != null) {
            for (FileReviewDto.IssueDto issue : review.getIssues()) {
                if (issue.getType() != null) {
                    switch (issue.getType().toLowerCase()) {
                        case "bug" -> bugs++;
                        case "security" -> security++;
                        case "performance" -> performance++;
                    }
                }
            }
        }
        codeReviewRepository.save(
                CodeReview.builder()
                        .fileId(fileId)
                        .language(language)
                        .issues(serialize(review.getIssues()))
                        .suggestions(serialize(review.getSuggestions()))
                        .architectureInsights(serialize(review.getArchitectureInsights()))
                        .bugCount(bugs)
                        .securityCount(security)
                        .performanceCount(performance)
                        .build());
    }

    /** Serializes an object to a JSON string for DB storage. */
    private String serialize(Object obj) {
        try {
            return obj != null ? objectMapper.writeValueAsString(obj) : "[]";
        } catch (Exception e) {
            return "[]";
        }
    }
}
