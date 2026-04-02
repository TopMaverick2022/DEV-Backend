package com.developerev.controller;

import com.developerev.service.GitService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/git")
@RequiredArgsConstructor
public class GitController {

    private final GitService gitService;

    @GetMapping("/commits/{projectId}/activity")
    public ResponseEntity<List<Map<String, Object>>> getCommitActivity(@PathVariable("projectId") Long projectId) throws Exception {
        List<Map<String, Object>> activity = gitService.getCommitActivity(projectId);
        return ResponseEntity.ok(activity);
    }


    @PostMapping("/sync")
    public ResponseEntity<String> syncRepository(
            @RequestBody GitSyncRequest request) throws Exception {
        
        if (request.getRepoUrl() == null || request.getToken() == null || request.getProjectId() == null) {
            throw new IllegalArgumentException("Repo URL, token, and project ID are required.");
        }

        gitService.syncRepository(request.getRepoUrl(), request.getToken(), request.getProjectId());
        return ResponseEntity.ok("Repository successfully synced to server workspace.");
    }

    @PostMapping("/push")
    public ResponseEntity<String> pushChanges(
            @RequestBody GitPushRequest request) throws Exception {

        if (request.getToken() == null || request.getProjectId() == null) {
            throw new IllegalArgumentException("Token and project ID are required.");
        }

        String message = request.getCommitMessage() != null && !request.getCommitMessage().isBlank() 
                         ? request.getCommitMessage() 
                         : "AI Auto-Commit from DeveloperEv Dashboard";

        gitService.pushChanges(request.getProjectId(), request.getToken(), message);
        return ResponseEntity.ok("Changes successfully pushed to remote repository.");
    }

    // ── File Explorer Endpoints ──────────────────────────────────────────────

    @GetMapping("/files/{projectId}")
    public ResponseEntity<List<FileItemDto>> listFiles(
            @PathVariable("projectId") Long projectId,
            @RequestParam(value = "path", required = false, defaultValue = "") String relativePath) throws Exception {
        File repoDir = gitService.getRepoDir(projectId);
        if (!repoDir.exists()) {
            throw new jakarta.persistence.EntityNotFoundException("Repository not found for project " + projectId);
        }

        File targetDir = new File(repoDir, relativePath);
        // Security check: ensure targetDir is within repoDir
        if (!targetDir.getCanonicalPath().startsWith(repoDir.getCanonicalPath())) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied: Invalid path");
        }

        if (!targetDir.exists() || !targetDir.isDirectory()) {
            throw new jakarta.persistence.EntityNotFoundException("Directory not found: " + relativePath);
        }

        File[] files = targetDir.listFiles((dir, name) -> !name.equals(".git"));
        List<FileItemDto> items = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                items.add(new FileItemDto(file.getName(), file.isDirectory(), file.length()));
            }
        }
        // Sort: directories first, then alphabetical
        items.sort((a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        return ResponseEntity.ok(items);
    }

    @GetMapping("/files/{projectId}/content")
    public ResponseEntity<String> getFileContent(
            @PathVariable("projectId") Long projectId,
            @RequestParam("path") String relativePath) throws Exception {
        File repoDir = gitService.getRepoDir(projectId);
        File targetFile = new File(repoDir, relativePath);

        // Security check
        if (!targetFile.getCanonicalPath().startsWith(repoDir.getCanonicalPath())) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied: Invalid path");
        }

        if (!targetFile.exists() || targetFile.isDirectory()) {
            throw new jakarta.persistence.EntityNotFoundException("File not found: " + relativePath);
        }

        // Return file content as string
        String content = Files.readString(targetFile.toPath());
        return ResponseEntity.ok(content);
    }

    @Data
    public static class GitSyncRequest {
        private String repoUrl;
        private String token;
        private Long projectId;
    }

    @Data
    public static class GitPushRequest {
        private String token;
        private Long projectId;
        private String commitMessage;
    }

    @Data
    public static class FileItemDto {
        private String name;
        private boolean isDirectory;
        private long size;

        public FileItemDto(String name, boolean isDirectory, long size) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.size = size;
        }
    }
}
