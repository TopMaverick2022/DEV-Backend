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

@RestController
@RequestMapping("/api/git")
@RequiredArgsConstructor
public class GitController {

    private final GitService gitService;

    @PostMapping("/sync")
    public ResponseEntity<String> syncRepository(
            @RequestBody GitSyncRequest request) {
        
        if (request.getRepoUrl() == null || request.getToken() == null || request.getProjectId() == null) {
            return ResponseEntity.badRequest().body("repoUrl, token, and projectId are required.");
        }

        try {
            gitService.syncRepository(request.getRepoUrl(), request.getToken(), request.getProjectId());
            return ResponseEntity.ok("Repository successfully synced to server workspace.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/push")
    public ResponseEntity<String> pushChanges(
            @RequestBody GitPushRequest request) {

        if (request.getToken() == null || request.getProjectId() == null) {
            return ResponseEntity.badRequest().body("token and projectId are required.");
        }

        String message = request.getCommitMessage() != null && !request.getCommitMessage().isBlank() 
                         ? request.getCommitMessage() 
                         : "AI Auto-Commit from DeveloperEv Dashboard";

        try {
            gitService.pushChanges(request.getProjectId(), request.getToken(), message);
            return ResponseEntity.ok("Changes successfully pushed to remote repository.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── File Explorer Endpoints ──────────────────────────────────────────────

    @GetMapping("/files/{projectId}")
    public ResponseEntity<List<FileItemDto>> listFiles(
            @PathVariable("projectId") Long projectId,
            @RequestParam(value = "path", required = false, defaultValue = "") String relativePath) {
        try {
            File repoDir = gitService.getRepoDir(projectId);
            if (!repoDir.exists()) {
                return ResponseEntity.badRequest().build();
            }

            File targetDir = new File(repoDir, relativePath);
            // Security check: ensure targetDir is within repoDir
            if (!targetDir.getCanonicalPath().startsWith(repoDir.getCanonicalPath())) {
                return ResponseEntity.badRequest().build();
            }

            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return ResponseEntity.badRequest().build();
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
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/files/{projectId}/content")
    public ResponseEntity<String> getFileContent(
            @PathVariable("projectId") Long projectId,
            @RequestParam("path") String relativePath) {
        try {
            File repoDir = gitService.getRepoDir(projectId);
            File targetFile = new File(repoDir, relativePath);

            // Security check
            if (!targetFile.getCanonicalPath().startsWith(repoDir.getCanonicalPath())) {
                return ResponseEntity.badRequest().body("Access denied");
            }

            if (!targetFile.exists() || targetFile.isDirectory()) {
                return ResponseEntity.badRequest().body("File not found");
            }

            // Return file content as string
            String content = Files.readString(targetFile.toPath());
            return ResponseEntity.ok(content);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to read file: " + e.getMessage());
        }
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
