package com.developerev.controller;

import com.developerev.model.CodeProject;
import com.developerev.repository.CodeProjectRepository;
import com.developerev.repository.ProjectRepository;
import com.developerev.service.CodeReviewService;
import com.developerev.service.DirectoryScannerService;
import com.developerev.service.GitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams real-time analysis progress to the frontend via Server-Sent Events (SSE).
 *
 * GET /api/ai/analyze-workspace/{projectId}/stream
 *
 * SSE event format:
 *   data: {"current":3,"total":47,"filename":"AuthService.java"}
 *   data: COMPLETE
 *   data: ERROR:<message>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AnalysisProgressController {

    private final CodeReviewService codeReviewService;
    private final DirectoryScannerService directoryScannerService;
    private final CodeProjectRepository codeProjectRepository;
    private final ProjectRepository projectRepository;
    private final GitService gitService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @GetMapping(value = "/analyze-workspace/{projectId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAnalysis(
            @PathVariable("projectId") Long projectId,
            @RequestParam(value = "projectName", required = false, defaultValue = "Workspace") String projectName) {

        // 10-minute timeout — large projects may take a while
        SseEmitter emitter = new SseEmitter(600_000L);

        executor.submit(() -> {
            try {
                Path workspaceDir = Paths.get("workspaces", "project_" + projectId)
                        .toAbsolutePath().normalize();

                if (!Files.exists(workspaceDir)) {
                    emitter.send(SseEmitter.event().data("ERROR:Workspace not found for project " + projectId));
                    emitter.complete();
                    return;
                }

                // Scan to get total file count for progress context
                List<java.nio.file.Path> allFiles = directoryScannerService.scan(workspaceDir);
                int capped = Math.min(allFiles.size(), 50);

                emitter.send(SseEmitter.event().data(
                        "{\"current\":0,\"total\":" + capped + ",\"filename\":\"Starting analysis...\"}"));

                // Create a CodeProject entity
                CodeProject project = codeProjectRepository.save(
                        CodeProject.builder()
                                .name(projectName)
                                .linkedProjectId(projectId)
                                .build());

                // Run analysis with progress callback
                codeReviewService.processFilesInBatches(
                        allFiles,
                        project,
                        workspaceDir,
                        progressEvent -> {
                            try {
                                // progressEvent format: "current/total:filename"
                                String[] parts = progressEvent.split(":", 2);
                                String[] counts = parts[0].split("/");
                                int current = Integer.parseInt(counts[0]);
                                int total = Integer.parseInt(counts[1]);
                                String filename = parts.length > 1 ? parts[1] : "...";

                                emitter.send(SseEmitter.event().data(
                                        "{\"current\":" + current + ",\"total\":" + total +
                                        ",\"filename\":\"" + filename.replace("\"", "\\\"") + "\"}"));
                            } catch (Exception e) {
                                log.warn("Failed to send SSE progress event: {}", e.getMessage());
                            }
                        });

                // Save last analyzed commit SHA
                projectRepository.findById(projectId).ifPresent(p -> {
                    String latestCommit = gitService.getLatestCommitSha(
                            p.getGithubRepoUrl() != null ? p.getGithubRepoUrl() : "", projectId);
                    if (latestCommit != null) {
                        p.setLastAnalyzedCommit(latestCommit);
                        projectRepository.save(p);
                    }
                });

                emitter.send(SseEmitter.event().data("COMPLETE"));
                emitter.complete();

            } catch (Exception e) {
                log.error("SSE analysis stream failed for project {}: {}", projectId, e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().data("ERROR:" + e.getMessage()));
                    emitter.complete();
                } catch (Exception sendEx) {
                    emitter.completeWithError(sendEx);
                }
            }
        });

        return emitter;
    }
}
