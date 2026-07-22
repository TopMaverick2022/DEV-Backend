package com.developerev.service;

import com.developerev.model.CodeFile;
import com.developerev.model.CodeReview;
import com.developerev.model.CodeProject;
import com.developerev.repository.CodeFileRepository;
import com.developerev.repository.CodeReviewRepository;
import com.developerev.repository.CodeProjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * AI-powered code fixer service.
 *
 * Fetches all issues for a project's files, reads the actual source file from
 * the workspace on disk, constructs a highly precise LLM prompt with each
 * issue listed explicitly, and returns AI-generated fixed code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFixerService {

    private final CodeProjectRepository codeProjectRepository;
    private final CodeFileRepository    codeFileRepository;
    private final CodeReviewRepository  codeReviewRepository;
    private final GitService            gitService;
    private final ObjectMapper          objectMapper;
    // Reuse the main AI client from the platform
    private final com.developerev.service.GeminiClient aiClient;

    // ─────────────────────────────────────────────────────────────────────────
    // Aggregate all issues per file
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a list of files with their issues (security, bugs, performance)
     * for the given master project id.
     */
    public List<Map<String, Object>> getAllIssues(Long masterProjectId) {
        CodeProject cp = getLatestCodeProject(masterProjectId);
        if (cp == null) return List.of();

        List<CodeFile>    files  = codeFileRepository.findByProjectId(cp.getId());
        List<Map<String, Object>> result = new ArrayList<>();

        for (CodeFile file : files) {
            List<CodeReview> reviews = codeReviewRepository.findByFileId(file.getId());
            List<Map<String, Object>> allIssues = new ArrayList<>();

            for (CodeReview review : reviews) {
                try {
                    List<JsonNode> issues = objectMapper.readValue(
                            review.getIssues() != null ? review.getIssues() : "[]",
                            new TypeReference<>() {});
                    for (JsonNode issue : issues) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("line",    issue.path("line").asInt(0));
                        item.put("type",    issue.path("type").asText(""));
                        item.put("message", issue.path("message").asText(""));
                        allIssues.add(item);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse issues for {}: {}", file.getFilename(), e.getMessage());
                }
            }

            if (!allIssues.isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("fileId",    file.getId());
                entry.put("filename",  file.getFilename());
                entry.put("path",      file.getPath());
                entry.put("language",  reviews.isEmpty() ? "Unknown" : reviews.get(0).getLanguage());
                entry.put("issues",    allIssues);
                result.add(entry);
            }
        }

        // Sort: most issues first
        result.sort((a, b) -> Integer.compare(
                ((List<?>) b.get("issues")).size(),
                ((List<?>) a.get("issues")).size()));

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fix a single file
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fix all issues in a single file.
     *
     * @param masterProjectId the linked (master) project id
     * @param filePath        relative path inside the workspace, e.g. "src/main/App.java"
     * @return map with keys: fixedCode (String), summary (String), originalCode (String)
     */
    @Transactional
    public Map<String, Object> fixFile(Long masterProjectId, String filePath) {
        // 1. Locate the workspace root
        File workspaceDir = gitService.getRepoDir(masterProjectId);
        if (!workspaceDir.exists()) {
            throw new RuntimeException("Workspace not found for project " + masterProjectId +
                    ". Please upload or sync your project first.");
        }

        // 2. Read the original source code
        Path sourcePath = workspaceDir.toPath().resolve(filePath).normalize();
        if (!sourcePath.startsWith(workspaceDir.toPath())) {
            throw new SecurityException("Path traversal attempt detected: " + filePath);
        }
        if (!Files.exists(sourcePath)) {
            throw new RuntimeException("File not found in workspace: " + filePath);
        }

        String originalCode;
        try {
            originalCode = Files.readString(sourcePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file: " + filePath, e);
        }

        // 3. Fetch issues for this file from DB
        CodeProject cp = getLatestCodeProject(masterProjectId);
        List<Map<String, Object>> issues = new ArrayList<>();
        if (cp != null) {
            List<CodeFile> codeFiles = codeFileRepository.findByProjectId(cp.getId());
            for (CodeFile cf : codeFiles) {
                if (filePath.equals(cf.getPath()) || cf.getPath().endsWith(filePath)) {
                    List<CodeReview> reviews = codeReviewRepository.findByFileId(cf.getId());
                    for (CodeReview review : reviews) {
                        try {
                            List<JsonNode> rawIssues = objectMapper.readValue(
                                    review.getIssues() != null ? review.getIssues() : "[]",
                                    new TypeReference<>() {});
                            for (JsonNode node : rawIssues) {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("line",    node.path("line").asInt(0));
                                item.put("type",    node.path("type").asText(""));
                                item.put("message", node.path("message").asText(""));
                                issues.add(item);
                            }
                        } catch (Exception ignored) {}
                    }
                    break;
                }
            }
        }

        // 4. Detect language from extension
        String language = detectLanguageFromPath(filePath);

        // 5. Build the expert AI prompt
        String prompt = buildFixPrompt(filePath, language, originalCode, issues);

        // 6. Call AI
        log.info("Sending AI fix request for file: {} ({} issues)", filePath, issues.size());
        String aiResponse = aiClient.generateContent(prompt);

        // 7. Strip any markdown fences the AI might add
        String fixedCode = stripMarkdownFences(aiResponse);

        // 8. Build a short summary by asking AI what changed
        String summary = buildChangeSummary(filePath, language, issues);

        // 8.5 Write the fixed code back to the workspace file on disk
        try {
            Files.writeString(sourcePath, fixedCode, StandardCharsets.UTF_8);
            log.info("Saved fixed code to disk: {}", sourcePath);
        } catch (Exception e) {
            log.error("Failed to write fixed code to file: {}", sourcePath, e);
            throw new RuntimeException("Failed to save fixed code to disk: " + e.getMessage(), e);
        }

        // Force Git to track this modification so the Project Explorer immediately sees it
        try {
            File repoDir = gitService.getRepoDir(masterProjectId);
            if (new File(repoDir, ".git").exists()) {
                try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repoDir)) {
                    git.add().addFilepattern(filePath).call();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to stage fixed file in git: {}", e.getMessage());
        }

        // 9. Mark issues as resolved in DB — zero out counts and clear issues JSON
        //    so that the dashboard stats reflect the fix immediately
        if (cp != null) {
            List<CodeFile> codeFiles = codeFileRepository.findByProjectId(cp.getId());
            for (CodeFile cf : codeFiles) {
                if (filePath.equals(cf.getPath()) || cf.getPath().endsWith(filePath)) {
                    List<CodeReview> reviews = codeReviewRepository.findByFileId(cf.getId());
                    for (CodeReview review : reviews) {
                        review.setBugCount(0);
                        review.setSecurityCount(0);
                        review.setPerformanceCount(0);
                        review.setCodeQualityCount(0);
                        review.setIssues("[]");
                        codeReviewRepository.save(review);
                    }
                    log.info("Cleared issue counts for fixed file: {}", filePath);
                    break;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath",     filePath);
        result.put("originalCode", originalCode);
        result.put("fixedCode",    fixedCode);
        result.put("summary",      summary);
        result.put("issueCount",   issues.size());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fix all files at once
    // ─────────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> fixAllFiles(Long masterProjectId, List<String> filePaths) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String path : filePaths) {
            try {
                results.add(fixFile(masterProjectId, path));
            } catch (Exception e) {
                log.error("Failed to fix file {}: {}", path, e.getMessage());
                Map<String, Object> errorEntry = new LinkedHashMap<>();
                errorEntry.put("filePath",  path);
                errorEntry.put("error",     e.getMessage());
                errorEntry.put("fixedCode", null);
                results.add(errorEntry);
            }
        }
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildFixPrompt(String filePath, String language,
                                   String originalCode, List<Map<String, Object>> issues) {
        StringBuilder issueList = new StringBuilder();
        if (issues.isEmpty()) {
            issueList.append("  - No specific issues found; please perform a general code quality improvement pass.");
        } else {
            for (int i = 0; i < issues.size(); i++) {
                Map<String, Object> issue = issues.get(i);
                issueList.append(String.format("  %d. [Line %s] [%s] %s%n",
                        i + 1,
                        issue.getOrDefault("line", "?"),
                        issue.getOrDefault("type", "Issue"),
                        issue.getOrDefault("message", "")));
            }
        }

        return """
                You are a world-class senior software engineer and security expert performing a critical production code fix.

                FILE: %s
                LANGUAGE: %s

                KNOWN ISSUES THAT MUST BE FIXED:
                %s

                INSTRUCTIONS:
                1. Fix EVERY issue listed above. Do not skip any.
                2. Add a concise inline comment directly above or beside each fix explaining what you changed and WHY.
                3. Make MINIMAL changes — do not refactor, rename, or restructure code unrelated to the listed issues.
                4. Preserve all existing code style, indentation, blank lines, and unrelated comments exactly.
                5. If a security issue involves SQL injection, XSS, authentication bypass, or secret exposure — apply the industry-standard secure fix.
                6. If a bug involves null checks, off-by-one errors, or incorrect logic — fix only that specific logic.
                7. If a performance issue involves O(n²) loops, repeated DB queries, or memory leaks — apply the optimal fix.
                8. RETURN ONLY THE COMPLETE FIXED FILE CONTENT — no markdown code fences (no ```), no explanations outside the code, no preamble.

                ORIGINAL FILE CONTENT:
                %s
                """.formatted(filePath, language, issueList, originalCode);
    }

    private String buildChangeSummary(String filePath, String language,
                                       List<Map<String, Object>> issues) {
        if (issues.isEmpty()) return "General code quality improvements applied.";
        long security = issues.stream().filter(i -> "security".equalsIgnoreCase(i.get("type").toString())).count();
        long bugs     = issues.stream().filter(i -> "bug".equalsIgnoreCase(i.get("type").toString())).count();
        long perf     = issues.stream().filter(i -> "performance".equalsIgnoreCase(i.get("type").toString())).count();
        List<String> parts = new ArrayList<>();
        if (security > 0) parts.add(security + " security vulnerabilit" + (security > 1 ? "ies" : "y"));
        if (bugs > 0)     parts.add(bugs + " bug" + (bugs > 1 ? "s" : ""));
        if (perf > 0)     parts.add(perf + " performance issue" + (perf > 1 ? "s" : ""));
        return "Fixed " + String.join(", ", parts) + " in " + filePath + ".";
    }

    private String stripMarkdownFences(String response) {
        String cleaned = response.trim();
        // Remove ```lang or ``` at the start
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline != -1) cleaned = cleaned.substring(firstNewline + 1);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf("```")).trim();
        }
        return cleaned;
    }

    private String detectLanguageFromPath(String path) {
        if (path == null) return "Unknown";
        String lower = path.toLowerCase();
        if (lower.endsWith(".java"))   return "Java";
        if (lower.endsWith(".ts"))     return "TypeScript";
        if (lower.endsWith(".tsx"))    return "TypeScript (React)";
        if (lower.endsWith(".js"))     return "JavaScript";
        if (lower.endsWith(".jsx"))    return "JavaScript (React)";
        if (lower.endsWith(".py"))     return "Python";
        if (lower.endsWith(".go"))     return "Go";
        if (lower.endsWith(".cs"))     return "C#";
        if (lower.endsWith(".php"))    return "PHP";
        if (lower.endsWith(".rb"))     return "Ruby";
        if (lower.endsWith(".kt"))     return "Kotlin";
        if (lower.endsWith(".swift")) return "Swift";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc")) return "C++";
        if (lower.endsWith(".c"))      return "C";
        if (lower.endsWith(".rs"))     return "Rust";
        return "Unknown";
    }

    private CodeProject getLatestCodeProject(Long masterProjectId) {
        return codeProjectRepository.findTopByLinkedProjectIdOrderByIdDesc(masterProjectId);
    }
}
