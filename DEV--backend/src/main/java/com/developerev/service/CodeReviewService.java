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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    public ProjectReviewResponseDto reviewProject(MultipartFile zipFile) throws IOException {

        log.info("ZIP received: {}", zipFile.getOriginalFilename());

        // ── Stage 1: Persist project record ───────────────────────────────
        CodeProject project = codeProjectRepository.save(
                CodeProject.builder()
                        .name(zipFile.getOriginalFilename())
                        .build());
        log.info("Created CodeProject id={} name={}", project.getId(), project.getName());

        // ── Stage 2: Extract ZIP to temp directory ─────────────────────────
        Path tempDir = zipExtractorService.extractZip(zipFile);

        List<FileReviewDto> fileReviews = new ArrayList<>();

        try {
            // ── Stage 3: Scan for all source files ─────────────────────────
            List<Path> sourceFiles = directoryScannerService.scan(tempDir);

            for (Path file : sourceFiles) {

                // ── Stage 4: Detect language ──────────────────────────────
                String language = languageDetectionService.detectLanguage(file);
                String filename = file.getFileName().toString();

                // ── Stage 5: Read content ─────────────────────────────────
                String content = fileContentService.readFile(file);
                if (content.isBlank())
                    continue;

                // ── Stage 6: Persist file record ──────────────────────────
                CodeFile codeFile = codeFileRepository.save(
                        CodeFile.builder()
                                .projectId(project.getId())
                                .filename(filename)
                                .path(tempDir.relativize(file).toString().replace("\\", "/"))
                                .build());

                // ── Stage 7: Build prompt + call AI ───────────────────────
                log.info("Sending file to AI: {} [{}]", filename, language);
                String prompt = aiPromptBuilder.buildPrompt(filename, language, content);
                FileReviewDto review = callAiAndParse(filename, language,
                        tempDir.relativize(file).toString().replace("\\", "/"), prompt);

                // ── Stage 8: Persist review result ────────────────────────
                persistReview(codeFile.getId(), language, review);

                fileReviews.add(review);
            }

        } finally {
            // ── Stage 9: Always clean up temp directory ────────────────────
            zipExtractorService.cleanup(tempDir);
        }

        log.info("Code review complete: {} files reviewed for project id={}",
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
     * Calls Gemini and parses the JSON response into a FileReviewDto.
     * Returns an empty review (not null) on any parse/network failure
     * so one bad file does not abort the entire project review.
     */
    private FileReviewDto callAiAndParse(String filename, String language,
            String relativePath, String prompt) {
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

            FileReviewDto dto = objectMapper.readValue(cleaned, FileReviewDto.class);
            dto.setFilename(filename);
            dto.setPath(relativePath);
            dto.setLanguage(language);
            return dto;

        } catch (Exception e) {
            log.warn("AI review failed for '{}' [{}]: {}", filename, language, e.getMessage());
            FileReviewDto dto = new FileReviewDto();
            dto.setFilename(filename);
            dto.setPath(relativePath);
            dto.setLanguage(language);
            dto.setIssues(List.of());
            dto.setSuggestions(List.of());
            dto.setArchitectureInsights(List.of());
            return dto;
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
