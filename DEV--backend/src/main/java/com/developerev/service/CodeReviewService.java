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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewService {

    private final GeminiClient geminiClient;
    private final CodeProjectRepository codeProjectRepository;
    private final CodeFileRepository codeFileRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ObjectMapper objectMapper;

    /** File extensions that will be reviewed. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".js", ".ts", ".py", ".go", ".kt", ".cs", ".cpp", ".c");

    /** Path segments that should be skipped entirely. */
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "node_modules", "target", "build", ".git", ".idea", "__pycache__", "dist");

    /** Max characters sent to AI per file to stay within token limits. */
    private static final int MAX_FILE_CHARS = 4000;

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    public ProjectReviewResponseDto reviewProject(MultipartFile zipFile) throws IOException {

        // 1. Persist the project record
        CodeProject project = CodeProject.builder()
                .name(zipFile.getOriginalFilename())
                .build();
        project = codeProjectRepository.save(project);
        log.info("Created CodeProject id={} name={}", project.getId(), project.getName());

        List<FileReviewDto> fileReviews = new ArrayList<>();

        // 2. Stream through the zip entries
        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {

                String entryPath = entry.getName().replace("\\", "/");

                // Skip directories and ignored paths
                if (entry.isDirectory() || isIgnored(entryPath)) {
                    zis.closeEntry();
                    continue;
                }

                // Skip unsupported file types
                if (!isSupportedExtension(entryPath)) {
                    zis.closeEntry();
                    continue;
                }

                String filename = extractFilename(entryPath);
                log.info("Reviewing file: {}", entryPath);

                // 3. Read file content (without closing the ZipInputStream)
                String content = readEntry(zis);
                if (content.isBlank()) {
                    zis.closeEntry();
                    continue;
                }

                // 4. Persist the file record
                final Long projectId = project.getId();
                CodeFile codeFile = CodeFile.builder()
                        .projectId(projectId)
                        .filename(filename)
                        .path(entryPath)
                        .build();
                codeFile = codeFileRepository.save(codeFile);

                // 5. Call AI for review
                FileReviewDto review = callAiReview(filename, content, entryPath);

                // 6. Persist the review result
                final Long fileId = codeFile.getId();
                CodeReview codeReview = CodeReview.builder()
                        .fileId(fileId)
                        .issues(serializeList(review.getIssues()))
                        .suggestions(serializeList(review.getSuggestions()))
                        .build();
                codeReviewRepository.save(codeReview);

                fileReviews.add(review);
                zis.closeEntry();
            }
        }

        log.info("Code review complete: {} files reviewed for project id={}",
                fileReviews.size(), project.getId());

        return new ProjectReviewResponseDto(
                project.getId(),
                project.getName(),
                fileReviews.size(),
                fileReviews);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if the entry path contains any ignored directory segment. */
    private boolean isIgnored(String path) {
        for (String ignored : IGNORED_DIRECTORIES) {
            if (path.contains("/" + ignored + "/") || path.startsWith(ignored + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if the file has a supported extension. */
    private boolean isSupportedExtension(String path) {
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (path.endsWith(ext))
                return true;
        }
        return false;
    }

    /** Extracts just the filename from a full path. */
    private String extractFilename(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * Reads the current ZipEntry's content into a String.
     * Truncates at MAX_FILE_CHARS to stay within AI token limits.
     */
    private String readEntry(ZipInputStream zis) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(zis, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
            if (sb.length() >= MAX_FILE_CHARS) {
                sb.append("\n... [truncated for review] ...");
                break;
            }
        }
        return sb.toString();
    }

    /** Calls Gemini to review a single file, returns parsed FileReviewDto. */
    private FileReviewDto callAiReview(String filename, String content, String path) {
        String prompt = """
                You are a senior software engineer performing a professional code review.

                Analyze the following code and identify:
                1. Bugs
                2. Security vulnerabilities
                3. Performance issues
                4. Code quality problems
                5. Best practice violations

                Return ONLY valid JSON. No markdown. No explanations.

                Response format:
                {
                  "issues": [
                    {
                      "line": 12,
                      "type": "Bug",
                      "message": "Possible null pointer"
                    }
                  ],
                  "suggestions": [
                    {
                      "line": 25,
                      "message": "Use dependency injection"
                    }
                  ]
                }

                File: %s
                Code:
                ```
                %s
                ```
                """.formatted(filename, content);

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
            dto.setPath(path);
            return dto;

        } catch (Exception e) {
            log.warn("AI review failed for file '{}': {}", filename, e.getMessage());
            // Return empty review on parse failure so one bad file doesn't abort all others
            FileReviewDto dto = new FileReviewDto();
            dto.setFilename(filename);
            dto.setPath(path);
            dto.setIssues(List.of());
            dto.setSuggestions(List.of());
            return dto;
        }
    }

    /** Serializes a list to a JSON string for DB storage. */
    private String serializeList(Object list) {
        try {
            return list != null ? objectMapper.writeValueAsString(list) : "[]";
        } catch (Exception e) {
            return "[]";
        }
    }
}
