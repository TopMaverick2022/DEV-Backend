package com.developerev.controller;

import com.developerev.model.CodeFile;
import com.developerev.model.CodeProject;
import com.developerev.model.CodeReview;
import com.developerev.repository.CodeFileRepository;
import com.developerev.repository.CodeProjectRepository;
import com.developerev.repository.CodeReviewRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Provides drill-down issue data for each dashboard stat card.
 *
 * GET /api/projects/{projectId}/issues/security   — Security issues per file
 * GET /api/projects/{projectId}/issues/bugs       — Bug issues per file
 * GET /api/projects/{projectId}/issues/performance — Performance issues per file
 * GET /api/projects/{projectId}/health-breakdown  — Health score breakdown
 * GET /api/projects/{projectId}/tech-debt         — Tech debt breakdown per file
 */
@Slf4j
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectIssuesController {

    private final CodeProjectRepository codeProjectRepository;
    private final CodeFileRepository codeFileRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ObjectMapper objectMapper;

    // ── Security Issues ───────────────────────────────────────────────────────

    @GetMapping("/{projectId}/issues/security")
    public ResponseEntity<List<Map<String, Object>>> getSecurityIssues(@PathVariable Long projectId) {
        return ResponseEntity.ok(getIssuesByType(projectId, "security"));
    }

    // ── Bug Issues ────────────────────────────────────────────────────────────

    @GetMapping("/{projectId}/issues/bugs")
    public ResponseEntity<List<Map<String, Object>>> getBugIssues(@PathVariable Long projectId) {
        return ResponseEntity.ok(getIssuesByType(projectId, "bug"));
    }

    // ── Performance Issues ────────────────────────────────────────────────────

    @GetMapping("/{projectId}/issues/performance")
    public ResponseEntity<List<Map<String, Object>>> getPerformanceIssues(@PathVariable Long projectId) {
        return ResponseEntity.ok(getIssuesByType(projectId, "performance"));
    }

    // ── Health Score Breakdown ────────────────────────────────────────────────

    @GetMapping("/{projectId}/health-breakdown")
    public ResponseEntity<Map<String, Object>> getHealthBreakdown(@PathVariable Long projectId) {
        CodeProject cp = getLatestCodeProject(projectId);
        if (cp == null) return ResponseEntity.ok(Map.of("score", 0, "files", List.of()));

        List<CodeFile> files = codeFileRepository.findByProjectId(cp.getId());
        List<Map<String, Object>> fileBreakdowns = new ArrayList<>();
        int totalBugs = 0, totalSecurity = 0, totalPerf = 0;

        for (CodeFile file : files) {
            List<CodeReview> reviews = codeReviewRepository.findByFileId(file.getId());
            int bugs = 0, security = 0, perf = 0;
            for (CodeReview r : reviews) {
                bugs += r.getBugCount();
                security += r.getSecurityCount();
                perf += r.getPerformanceCount();
            }
            totalBugs += bugs;
            totalSecurity += security;
            totalPerf += perf;

            int fileDelta = (bugs * 2) + (security * 5) + perf;
            if (fileDelta > 0) {
                List<String> reasons = new ArrayList<>();
                if (security > 0) reasons.add(security + " security issue" + (security > 1 ? "s" : "") + " (+" + (security * 5) + " penalty)");
                if (bugs > 0) reasons.add(bugs + " bug" + (bugs > 1 ? "s" : "") + " (+" + (bugs * 2) + " penalty)");
                if (perf > 0) reasons.add(perf + " performance issue" + (perf > 1 ? "s" : "") + " (+" + perf + " penalty)");

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filename", file.getFilename());
                entry.put("path", file.getPath());
                entry.put("pointsDeducted", fileDelta);
                entry.put("reasons", reasons);
                fileBreakdowns.add(entry);
            }
        }

        // Sort by most deductions first
        fileBreakdowns.sort((a, b) -> Integer.compare((int) b.get("pointsDeducted"), (int) a.get("pointsDeducted")));

        double penalty = (totalBugs * 2.0) + (totalSecurity * 5.0) + (totalPerf * 1.0);
        double score = 100.0 * (100.0 / (100.0 + penalty));
        score = Math.round(score * 10.0) / 10.0;
        if (files.isEmpty()) score = 0.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", score);
        result.put("totalBugs", totalBugs);
        result.put("totalSecurityIssues", totalSecurity);
        result.put("totalPerformanceIssues", totalPerf);
        result.put("filesAnalyzed", files.size());
        result.put("files", fileBreakdowns);
        return ResponseEntity.ok(result);
    }

    // ── Tech Debt Breakdown ───────────────────────────────────────────────────

    @GetMapping("/{projectId}/tech-debt")
    public ResponseEntity<Map<String, Object>> getTechDebt(@PathVariable Long projectId) {
        CodeProject cp = getLatestCodeProject(projectId);
        if (cp == null) return ResponseEntity.ok(Map.of("totalHours", "0h", "files", List.of()));

        List<CodeFile> files = codeFileRepository.findByProjectId(cp.getId());
        List<Map<String, Object>> fileDebts = new ArrayList<>();
        double totalHours = 0;

        for (CodeFile file : files) {
            List<CodeReview> reviews = codeReviewRepository.findByFileId(file.getId());
            int bugs = 0, security = 0, perf = 0;
            for (CodeReview r : reviews) {
                bugs += r.getBugCount();
                security += r.getSecurityCount();
                perf += r.getPerformanceCount();
            }

            double fileHours = bugs * 1.0 + security * 3.0 + perf * 0.5;
            if (fileHours > 0) {
                totalHours += fileHours;
                List<Map<String, Object>> items = new ArrayList<>();
                if (security > 0) items.add(Map.of("type", "Security", "count", security, "hours", security * 3.0, "guide", "Remediate each security issue carefully. Test fixes thoroughly before deployment."));
                if (bugs > 0) items.add(Map.of("type", "Bug", "count", bugs, "hours", bugs * 1.0, "guide", "Reproduce the bug. Write a failing test. Fix. Verify test passes."));
                if (perf > 0) items.add(Map.of("type", "Performance","count", perf, "hours", perf * 0.5, "guide", "Profile the code path. Optimize the bottleneck. Re-measure after fix."));

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filename", file.getFilename());
                entry.put("path", file.getPath());
                entry.put("totalHours", String.format("%.1fh", fileHours));
                entry.put("items", items);
                fileDebts.add(entry);
            }
        }

        fileDebts.sort((a, b) -> Double.compare(
                Double.parseDouble(((String) b.get("totalHours")).replace("h", "")),
                Double.parseDouble(((String) a.get("totalHours")).replace("h", ""))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalHours", String.format("%.1fh", totalHours));
        result.put("totalFiles", fileDebts.size());
        result.put("files", fileDebts);
        return ResponseEntity.ok(result);
    }

    // ── Shared helper ─────────────────────────────────────────────────────────

    private List<Map<String, Object>> getIssuesByType(Long projectId, String issueType) {
        CodeProject cp = getLatestCodeProject(projectId);
        if (cp == null) return List.of();

        List<CodeFile> files = codeFileRepository.findByProjectId(cp.getId());
        List<Map<String, Object>> result = new ArrayList<>();

        for (CodeFile file : files) {
            List<CodeReview> reviews = codeReviewRepository.findByFileId(file.getId());
            for (CodeReview review : reviews) {
                try {
                    List<JsonNode> allIssues = objectMapper.readValue(
                            review.getIssues() != null ? review.getIssues() : "[]",
                            new TypeReference<List<JsonNode>>() {});

                    List<Map<String, Object>> filtered = new ArrayList<>();
                    for (JsonNode issue : allIssues) {
                        String type = issue.path("type").asText("").toLowerCase();
                        if (type.equals(issueType) || type.startsWith(issueType.substring(0, 3))) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("line", issue.path("line").asInt(0));
                            item.put("type", issue.path("type").asText());
                            item.put("message", issue.path("message").asText());
                            filtered.add(item);
                        }
                    }

                    if (!filtered.isEmpty()) {
                        Map<String, Object> fileEntry = new LinkedHashMap<>();
                        fileEntry.put("filename", file.getFilename());
                        fileEntry.put("path", file.getPath());
                        fileEntry.put("language", review.getLanguage());
                        fileEntry.put("issues", filtered);
                        result.add(fileEntry);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse issues for file {}: {}", file.getFilename(), e.getMessage());
                }
            }
        }

        return result;
    }

    private CodeProject getLatestCodeProject(Long masterProjectId) {
        CodeProject cp = codeProjectRepository.findTopByLinkedProjectIdOrderByIdDesc(masterProjectId);
        return cp;
    }
}
