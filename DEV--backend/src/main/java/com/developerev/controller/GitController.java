package com.developerev.controller;

import com.developerev.service.GitService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;

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

        // Git Status Check
        Set<String> modifiedPaths = new HashSet<>();
        Set<String> addedPaths = new HashSet<>();
        boolean isGitRepo = new File(repoDir, ".git").exists();
        if (isGitRepo) {
            try (Git git = Git.open(repoDir)) {
                org.eclipse.jgit.api.Status status = git.status().call();
                for (String path : status.getModified()) { addPathAndParents(path, modifiedPaths); }
                for (String path : status.getChanged()) { addPathAndParents(path, modifiedPaths); }
                for (String path : status.getUntracked()) { addPathAndParents(path, addedPaths); }
                for (String path : status.getAdded()) { addPathAndParents(path, addedPaths); }
            } catch (Exception e) {
                // Ignore git errors
            }
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        File[] files = targetDir.listFiles((dir, name) -> !name.equals(".git"));
        List<FileItemDto> items = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                String fileRelPath = relativePath.isEmpty() ? file.getName() : (relativePath + "/" + file.getName());
                fileRelPath = fileRelPath.replace('\\', '/');

                String status = "NONE";
                if (addedPaths.contains(fileRelPath)) {
                    status = "ADDED";
                } else if (modifiedPaths.contains(fileRelPath)) {
                    status = "MODIFIED";
                }

                String lastModifiedText = sdf.format(new Date(file.lastModified()));
                items.add(new FileItemDto(file.getName(), file.isDirectory(), file.length(), status, lastModifiedText, file.lastModified()));
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
    public ResponseEntity<FileContentResponseDto> getFileContent(
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

        String content = Files.readString(targetFile.toPath());
        List<Integer> addedLines = new ArrayList<>();
        List<Integer> modifiedLines = new ArrayList<>();
        
        boolean isGitRepo = new File(repoDir, ".git").exists();
        if (isGitRepo) {
            try (Git git = Git.open(repoDir)) {
                String headContent = getFileContentFromHead(git, relativePath);
                if (headContent != null) {
                    org.eclipse.jgit.diff.RawText headText = new org.eclipse.jgit.diff.RawText(headContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    org.eclipse.jgit.diff.RawText currentText = new org.eclipse.jgit.diff.RawText(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    org.eclipse.jgit.diff.EditList edits = org.eclipse.jgit.diff.MyersDiff.INSTANCE.diff(org.eclipse.jgit.diff.RawTextComparator.DEFAULT, headText, currentText);
                    for (org.eclipse.jgit.diff.Edit edit : edits) {
                        if (edit.getType() == org.eclipse.jgit.diff.Edit.Type.INSERT) {
                            for (int i = edit.getBeginB() + 1; i <= edit.getEndB(); i++) {
                                addedLines.add(i);
                            }
                        } else if (edit.getType() == org.eclipse.jgit.diff.Edit.Type.REPLACE) {
                            for (int i = edit.getBeginB() + 1; i <= edit.getEndB(); i++) {
                                modifiedLines.add(i);
                            }
                        }
                    }
                } else {
                    // Untracked new file - all lines are added
                    int lineCount = countLines(content);
                    for (int i = 1; i <= lineCount; i++) {
                        addedLines.add(i);
                    }
                }
            } catch (Exception e) {
                // Ignore diff errors
            }
        }

        return ResponseEntity.ok(new FileContentResponseDto(content, addedLines, modifiedLines));
    }

    @GetMapping("/download/{projectId}")
    public void downloadProject(
            @PathVariable("projectId") Long projectId,
            jakarta.servlet.http.HttpServletResponse response) throws Exception {
        File repoDir = gitService.getRepoDir(projectId);
        if (!repoDir.exists()) {
            throw new jakarta.persistence.EntityNotFoundException("Repository not found for project " + projectId);
        }

        // Set response headers
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"project_" + projectId + ".zip\"");

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream());
             java.util.stream.Stream<Path> stream = java.nio.file.Files.walk(repoDir.toPath())) {
            Path sourcePath = repoDir.toPath();
            stream.filter(path -> !sourcePath.relativize(path).toString().startsWith(".git")) // Skip .git folder
                .forEach(path -> {
                    if (Files.isDirectory(path)) return; // Only zip files
                    String zipPath = sourcePath.relativize(path).toString().replace('\\', '/');
                    try {
                        java.util.zip.ZipEntry zipEntry = new java.util.zip.ZipEntry(zipPath);
                        zos.putNextEntry(zipEntry);
                        java.nio.file.Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void addPathAndParents(String path, Set<String> pathSet) {
        String normalized = path.replace('\\', '/');
        pathSet.add(normalized);
        int idx = normalized.lastIndexOf('/');
        while (idx > 0) {
            normalized = normalized.substring(0, idx);
            pathSet.add(normalized);
            idx = normalized.lastIndexOf('/');
        }
    }

    private String getFileContentFromHead(Git git, String relativePath) {
        try {
            org.eclipse.jgit.lib.Repository repository = git.getRepository();
            org.eclipse.jgit.lib.ObjectId headId = repository.resolve("HEAD^{tree}");
            if (headId == null) return null;
            try (org.eclipse.jgit.treewalk.TreeWalk treeWalk = org.eclipse.jgit.treewalk.TreeWalk.forPath(repository, relativePath, headId)) {
                if (treeWalk != null) {
                    org.eclipse.jgit.lib.ObjectId objectId = treeWalk.getObjectId(0);
                    org.eclipse.jgit.lib.ObjectLoader loader = repository.open(objectId);
                    return new String(loader.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private int countLines(String content) {
        if (content == null || content.isEmpty()) return 0;
        int count = 1;
        for (char c : content.toCharArray()) {
            if (c == '\n') count++;
        }
        return count;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    @Data
    public static class FileContentResponseDto {
        private String content;
        private List<Integer> addedLines;
        private List<Integer> modifiedLines;

        public FileContentResponseDto(String content, List<Integer> addedLines, List<Integer> modifiedLines) {
            this.content = content;
            this.addedLines = addedLines;
            this.modifiedLines = modifiedLines;
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
        private String status; // "ADDED", "MODIFIED", "NONE"
        private String lastModifiedText;
        private long lastModified;

        public FileItemDto(String name, boolean isDirectory, long size, String status, String lastModifiedText, long lastModified) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.size = size;
            this.status = status;
            this.lastModifiedText = lastModifiedText;
            this.lastModified = lastModified;
        }
    }
}
