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
    private final AiClient aiClient;

    // ── Persistence ────────────────────────────────────────────────────────
    private final CodeProjectRepository codeProjectRepository;
    private final CodeFileRepository codeFileRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ProjectService projectService;
    private final com.developerev.repository.ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final GitService gitService;

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    public ProjectReviewResponseDto reviewProject(MultipartFile zipFile, String username, Long linkedProjectId)
            throws IOException {

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

        // ── Stage 2: Clear old workspace dir first (removes stale .git, old files) ──
        Long targetId = linkedProjectId != null ? linkedProjectId : project.getId();

        Path workspaceDir;
        if (linkedProjectId != null) {
            workspaceDir = gitService.getRepoDir(linkedProjectId).toPath();
        } else {
            String dirName = project.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
            workspaceDir = java.nio.file.Paths.get("workspaces", dirName).toAbsolutePath().normalize();
        }

        if (java.nio.file.Files.exists(workspaceDir)) {
            log.info("Clearing existing workspace before ZIP extraction: {}", workspaceDir);
            org.springframework.util.FileSystemUtils.deleteRecursively(workspaceDir.toFile());
        }

        zipExtractorService.extractTo(zipFile, workspaceDir);
        log.info("Code upload complete and stored in workspace: {}", workspaceDir);

        // ── Stage 3: Reset commit tracking so incremental analysis won't skip ZIP
        // files ──
        if (linkedProjectId != null) {
            projectService.resetLastAnalyzedCommit(linkedProjectId);
        }

        return ProjectReviewResponseDto.builder()
                .projectId(targetId)
                .projectName(project.getName())
                .totalFilesReviewed(0)
                .fileReviews(new ArrayList<>())
                .status("UPLOADED")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Workspace entry point (for JGit clones, NO ZIP INVOLVED)
    // ─────────────────────────────────────────────────────────────────────────

    public ProjectReviewResponseDto reviewWorkspace(Path workspaceDir, String projectName, Long linkedProjectId)
            throws IOException {
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
                .projectId(linkedProjectId != null ? linkedProjectId : project.getId())
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
     * Chunks files, extracts contents, creates CodeFile entities, calls Gemini once
     * per batch,
     * and persists reviews, returning the combined FileReviewDto results.
     *
     * @param progressCallback optional SSE callback: receives
     *                         "current/total:filename" events
     */
    public List<FileReviewDto> processFilesInBatches(
            List<Path> sourceFiles, CodeProject project, Path rootDir,
            boolean isIncremental, Consumer<String> progressCallback) {
        List<FileReviewDto> allReviews = new ArrayList<>();

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

            if (Thread.currentThread().isInterrupted()) {
                log.info("Analysis interrupted during file metadata gathering.");
                return allReviews; // Exit early
            }

            if (content.isBlank()) {
                processedFiles++;
                if (progressCallback != null) {
                    progressCallback.accept(processedFiles + "/" + totalFiles + ":" + filename);
                }
                continue;
            }

            String fileHash = DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8));
            List<CodeFile> validCachedFiles = codeFileRepository.findByFileHashWithReview(fileHash);

            if (!validCachedFiles.isEmpty()) {
                // CACHE HIT — The new repository method ensures this file has a completed review
                CodeFile cachedFile = validCachedFiles.get(0);
                List<CodeReview> cachedReviews = codeReviewRepository.findByFileId(cachedFile.getId());

                // We know this is true because of the SQL query, but check just to be safe
                boolean hasRealCachedData = !cachedReviews.isEmpty();

                if (hasRealCachedData) {
                    CodeFile codeFile = codeFileRepository.save(
                            CodeFile.builder()
                                    .projectId(project.getId())
                                    .filename(filename)
                                    .path(relativePath)
                                    .fileHash(fileHash)
                                    .language(language)
                                    .lineCount(content.split("\n").length)
                                    .build());

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
                            dto.setIssues(objectMapper.readValue(cachedReview.getIssues(),
                                    new TypeReference<List<FileReviewDto.IssueDto>>() {
                                    }));
                        } else
                            dto.setIssues(new ArrayList<>());

                        if (cachedReview.getSuggestions() != null && !cachedReview.getSuggestions().equals("[]")) {
                            dto.setSuggestions(objectMapper.readValue(cachedReview.getSuggestions(),
                                    new TypeReference<List<FileReviewDto.SuggestionDto>>() {
                                    }));
                        } else
                            dto.setSuggestions(new ArrayList<>());

                        if (cachedReview.getArchitectureInsights() != null
                                && !cachedReview.getArchitectureInsights().equals("[]")) {
                            dto.setArchitectureInsights(objectMapper.readValue(cachedReview.getArchitectureInsights(),
                                    new TypeReference<List<String>>() {
                                    }));
                        } else
                            dto.setArchitectureInsights(new ArrayList<>());
                    } catch (Exception e) {
                        log.warn("Failed to parse cached review for {}: {}", filename, e.getMessage());
                        dto.setIssues(new ArrayList<>());
                        dto.setSuggestions(new ArrayList<>());
                        dto.setArchitectureInsights(new ArrayList<>());
                    }
                    allReviews.add(dto);
                    log.info("CACHE HIT (valid): Reusing analysis for file: {}", filename);

                    processedFiles++;
                    if (progressCallback != null) {
                        progressCallback.accept(processedFiles + "/" + totalFiles + ":" + filename);
                    }
                } else {
                    // Cache entry is empty/invalid — re-analyse this file from scratch
                    log.info("CACHE MISS (stale/empty result): Re-analysing file: {}", filename);
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
            } else {
                // CACHE MISS — brand new file, needs analysis
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

        // Process pending files in chunks of 3 (reduces JSON complexity and
        // hallucination risks)
        // Smaller batches significantly lower the chance of invalid JSON generation
        final int BATCH_SIZE = 7;
        int totalPending = pendingFiles.size();

        for (int i = 0; i < totalPending; i += BATCH_SIZE) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Batch processing interrupted. Cancelling analysis.");
                throw new RuntimeException("Analysis was cancelled by the user.");
            }

            int end = Math.min(i + BATCH_SIZE, totalPending);

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
                    filenames.size(), (i / BATCH_SIZE) + 1, (int) Math.ceil((double) totalPending / BATCH_SIZE));
            String prompt = aiPromptBuilder.buildBatchPrompt(filenames, languages, contents);

            try {
                List<FileReviewDto> batchReviews = callAiBatchAndParse(prompt, filenames, languages, rootDir,
                        batchPaths);

                for (FileReviewDto review : batchReviews) {
                    CodeFile matchingCodeFile = savedCodeFiles.stream()
                            .filter(cf -> cf.getFilename().equals(review.getFilename()))
                            .findFirst().orElse(null);

                    if (matchingCodeFile != null) {
                        persistReview(matchingCodeFile.getId(),
                                review.getLanguage() != null ? review.getLanguage() : "Unknown", review);
                        allReviews.add(review);
                    }
                }
            } catch (com.developerev.ai.exception.GeminiApiException e) {
                log.error("Critical AI API failure during batch ({}): {}", filenames, e.getMessage());
                throw e; // Always propagate Gemini 429/500 errors to the caller
            } catch (Exception e) {
                // IMPORTANT: Check if the exception was caused by a thread interrupt (cancellation)
                if (Thread.currentThread().isInterrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    log.info("Batch analysis interrupted. Stopping analysis for project {}.", project.getId());
                    throw new RuntimeException("Analysis was cancelled by the user.");
                }

                log.warn(
                        "Batch analysis failed due to parsing error for files {}. Error: {}. Falling back to single file analysis.",
                        filenames, e.getMessage());

                // Fallback to single file processing
                for (int j = 0; j < filenames.size(); j++) {
                    String singleFilename = filenames.get(j);
                    try {
                        log.info("Fallback: Analyzing single file: {}", singleFilename);
                        String singlePrompt = aiPromptBuilder.buildBatchPrompt(
                                List.of(singleFilename),
                                List.of(languages.get(j)),
                                List.of(contents.get(j)));
                        List<FileReviewDto> singleReview = callAiBatchAndParse(
                                singlePrompt,
                                List.of(singleFilename),
                                List.of(languages.get(j)),
                                rootDir,
                                List.of(batchPaths.get(j)));

                        for (FileReviewDto review : singleReview) {
                            CodeFile matchingCodeFile = savedCodeFiles.stream()
                                    .filter(cf -> cf.getFilename().equals(review.getFilename()))
                                    .findFirst().orElse(null);

                            if (matchingCodeFile != null) {
                                persistReview(matchingCodeFile.getId(),
                                        review.getLanguage() != null ? review.getLanguage() : "Unknown", review);
                                allReviews.add(review);
                            }
                        }
                    } catch (com.developerev.ai.exception.GeminiApiException gae) {
                        log.error("Critical AI API failure during fallback for file {}: {}", singleFilename,
                                gae.getMessage());
                        throw gae; // Propagate quota errors even in fallback
                    } catch (Exception singleEx) {
                        log.error("Fallback analysis also failed for file {}. Skipping file. Error: {}", singleFilename,
                                singleEx.getMessage());
                        // Do not throw, allow the rest of the batch to be processed
                    }
                }
            }

            if (i + BATCH_SIZE < totalPending) {
                try {
                    // 3-second cooldown between batches to reduce pressure on each free-tier key.
                    // With 7 keys and key rotation, this keeps per-key request rates manageable.
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("Batch processing sleep interrupted. Cancelling analysis.");
                    throw new RuntimeException("Analysis was cancelled by the user.", ie);
                }
            }
        }

        return allReviews;
    }

    /**
     * Calls Gemini for a BATCH of files and parses the JSON ARRAY response.
     * Uses regex to robustly extract the JSON array even when Gemini includes
     * "thinking" tags, markdown fences, or extra prefixes in its response.
     */
    private List<FileReviewDto> callAiBatchAndParse(String prompt, List<String> filenames, List<String> languages,
            Path rootDir, List<Path> batchPaths) {
        String aiResponseText = aiClient.generateContent(prompt);
        try {
            String jsonArray = extractJsonArray(aiResponseText);
            if (jsonArray == null || jsonArray.isBlank()) {
                log.error("AI response did not contain a valid JSON array. Raw text (first 500 chars): {}",
                        aiResponseText.substring(0, Math.min(aiResponseText.length(), 500)));
                throw new RuntimeException(
                        "AI response did not contain a parseable JSON array for batch: " + filenames);
            }

            List<FileReviewDto> dtos = objectMapper.readValue(jsonArray,
                    new com.fasterxml.jackson.core.type.TypeReference<List<FileReviewDto>>() {
                    });

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
            log.info("Successfully parsed AI batch response with {} file reviews", dtos.size());
            return dtos;

        } catch (com.developerev.ai.exception.GeminiApiException e) {
            // Rethrow Gemini API exceptions (429, 500, etc.) — let caller handle
            throw e;
        } catch (Exception e) {
            // Do NOT silently return 0 bugs. Rethrow so the caller logs a real error.
            log.error("AI batch review parsing failed for files {}. Raw response snippet: {}. Error: {}",
                    filenames,
                    aiResponseText.substring(0, Math.min(aiResponseText.length(), 500)),
                    e.getMessage());
            throw new RuntimeException(
                    "Failed to parse AI batch response for files: " + filenames + " — " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the first complete JSON array (starting with '[', ending with ']')
     * from
     * raw AI response text. This handles Gemini responses that include:
     * - Leading "thinking" text before the JSON
     * - Markdown code fences (```json ... ```)
     * - Trailing text after the closing bracket
     */
    private String extractJsonArray(String text) {
        if (text == null || text.isBlank())
            return null;

        String extracted = null;

        // 1. Try to find a ```json ... ``` code fence first
        java.util.regex.Matcher fenceMatcher = java.util.regex.Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```")
                .matcher(text);
        if (fenceMatcher.find()) {
            String fenced = fenceMatcher.group(1).trim();
            if (fenced.startsWith("["))
                extracted = fenced;
        }

        // 2. Find the first '[' and last ']' and extract everything in between
        if (extracted == null) {
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start != -1 && end != -1 && end > start) {
                extracted = text.substring(start, end + 1).trim();
            }
        }

        if (extracted != null) {
            // Sanitize JSON to fix common LLM hallucinations
            return extracted
                    .replaceAll("\\+\\s*\"", "\"") // remove + before strings
                    .replaceAll("\"\\s*\\+", "\"") // remove + after strings
                    .replaceAll(",\\s*]", "]") // fix trailing comma in arrays
                    .replaceAll(",\\s*}", "}"); // fix trailing comma in objects
        }

        return null;
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
