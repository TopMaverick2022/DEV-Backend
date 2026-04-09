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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.Future<?>> activeTasks = new java.util.concurrent.ConcurrentHashMap<>();


    @GetMapping(value = "/analyze-workspace/{projectId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAnalysis(
            @PathVariable("projectId") Long projectId,
            @RequestParam(value = "projectName", required = false, defaultValue = "Workspace") String projectName) {

        // 10-minute timeout — large projects may take a while
        SseEmitter emitter = new SseEmitter(600_000L);

        java.util.concurrent.Future<?> future = executor.submit(() -> {
            try {
                Path workspaceDir = Paths.get("workspaces", "project_" + projectId)
                        .toAbsolutePath().normalize();

                if (!Files.exists(workspaceDir)) {
                    emitter.send(SseEmitter.event().data("ERROR:Workspace not found for project " + projectId));
                    emitter.complete();
                    return;
                }

                // 1) Scan to get total file list
                List<java.nio.file.Path> allFiles = directoryScannerService.scan(workspaceDir);
                
                // 2) Smart Filter: Only analyze changed files if we have a baseline commit
                //    AND the workspace is actually a real git repository (not a ZIP upload)
                var masterProject = projectRepository.findById(projectId).orElse(null);
                boolean isIncremental = false;
                File gitDir = new File(workspaceDir.toFile(), ".git");

                if (masterProject != null
                        && masterProject.getLastAnalyzedCommit() != null
                        && gitDir.exists()  // Only apply incremental logic to real Git repos
                ) {
                    String currentSha = gitService.getLocalCommitSha(projectId);
                    if (currentSha != null && !currentSha.equals(masterProject.getLastAnalyzedCommit())) {
                        List<String> changedPaths = gitService.getChangedFiles(projectId, masterProject.getLastAnalyzedCommit(), currentSha);
                        if (!changedPaths.isEmpty()) {
                            allFiles = allFiles.stream()
                                    .filter(p -> {
                                        String rel = workspaceDir.relativize(p).toString().replace("\\", "/");
                                        return changedPaths.contains(rel);
                                    })
                                    .toList();
                            isIncremental = true;
                            log.info("Incremental analysis: processing {} changed files for project {}", allFiles.size(), projectId);
                        } else {
                            // No changes between commits
                            emitter.send(SseEmitter.event().data("{\"current\":0,\"total\":0,\"filename\":\"No new changes to analyze.\"}"));
                            emitter.send(SseEmitter.event().data("COMPLETE"));
                            emitter.complete();
                            return;
                        }
                    }
                } else if (masterProject != null
                        && masterProject.getLastAnalyzedCommit() != null
                        && !gitDir.exists()) {
                    // Workspace is from a ZIP upload — run a full analysis, ignore any stale commit SHA
                    log.info("Workspace for project {} has no .git dir (ZIP upload). Performing full analysis.", projectId);
                }


                emitter.send(SseEmitter.event().data(
                        "{\"current\":0,\"total\":" + allFiles.size() + ",\"filename\":\"" + 
                        (isIncremental ? "Analyzing recent changes..." : "Starting full analysis...") + "\"}"));

                // Create a CodeProject entity
                CodeProject project = codeProjectRepository.save(
                        CodeProject.builder()
                                .name(projectName + (isIncremental ? " (Incremental)" : ""))
                                .linkedProjectId(projectId)
                                .build());

                // Run analysis with progress callback
                codeReviewService.processFilesInBatches(
                        allFiles,
                        project,
                        workspaceDir,
                        isIncremental,
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
                if (masterProject != null) {
                    String latestCommit = gitService.getLatestCommitSha(
                            masterProject.getGithubRepoUrl() != null ? masterProject.getGithubRepoUrl() : "", projectId);
                    if (latestCommit != null) {
                        masterProject.setLastAnalyzedCommit(latestCommit);
                        projectRepository.save(masterProject);
                    }
                }

                emitter.send(SseEmitter.event().data("COMPLETE"));
                emitter.complete();

            } catch (Exception e) {
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    log.info("Analysis cancelled for project {}", projectId);
                    try {
                        emitter.send(SseEmitter.event().data("ERROR:Analysis was cancelled by the user."));
                        emitter.complete();
                    } catch (Exception sendEx) {
                        // ignore
                    }
                } else {
                    log.error("SSE analysis stream failed for project {}: {}", projectId, e.getMessage(), e);
                    try {
                        emitter.send(SseEmitter.event().data("ERROR:" + e.getMessage()));
                        emitter.complete();
                    } catch (Exception sendEx) {
                        emitter.completeWithError(sendEx);
                    }
                }
            } finally {
                activeTasks.remove(projectId);
            }
        });
        activeTasks.put(projectId, future);

        return emitter;
    }

    @PostMapping("/analyze-workspace/{projectId}/cancel")
    public org.springframework.http.ResponseEntity<String> cancelAnalysis(@PathVariable("projectId") Long projectId) {
        java.util.concurrent.Future<?> future = activeTasks.get(projectId);
        if (future != null) {
            future.cancel(true); // interrupts the running thread
            activeTasks.remove(projectId);
            return org.springframework.http.ResponseEntity.ok("Analysis cancelled successfully.");
        }
        return org.springframework.http.ResponseEntity.status(404).body("No active analysis found for this project.");
    }
}
