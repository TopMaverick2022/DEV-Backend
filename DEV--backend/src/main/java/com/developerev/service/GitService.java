package com.developerev.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class GitService {

    private static final String WORKSPACE_BASE = "workspaces";

    /**
     * Clones or pulls the latest changes from the repository into a local workspace.
     */
    public void syncRepository(String repoUrl, String token, Long projectId) {
        File repoDir = getRepoDir(projectId);
        UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(token, "");

        try {
            if (repoDir.exists() && new File(repoDir, ".git").exists()) {
                log.info("Repository exists for project {}, attempting to pull...", projectId);
                try (Git git = Git.open(repoDir)) {
                    git.pull().setCredentialsProvider(credentials).call();
                    log.info("Successfully pulled latest changes for project {}", projectId);
                    return;
                } catch (Exception e) {
                    log.warn("Pull failed for project {}, will perform fresh clone. Error: {}", projectId, e.getMessage());
                    // Fall back to clean clone if pull fails (e.g., origin changed, merge conflicts)
                    FileSystemUtils.deleteRecursively(repoDir);
                }
            }

            log.info("Cloning repository for project {} to {}", projectId, repoDir.getAbsolutePath());
            if (!repoDir.exists()) {
                Files.createDirectories(repoDir.toPath());
            } else {
                FileSystemUtils.deleteRecursively(repoDir);
            }

            Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(repoDir)
                    .setCredentialsProvider(credentials)
                    .setCloneAllBranches(false)
                    .call();
            log.info("Successfully cloned repository for project {}", projectId);

        } catch (GitAPIException | IOException e) {
            log.error("Failed to sync repository for project {}: {}", projectId, e.getMessage(), e);
            throw new RuntimeException("Failed to sync repository: " + e.getMessage());
        }
    }

    /**
     * Commits all changes in the workspace and pushes back to GitHub.
     */
    public void pushChanges(Long projectId, String token, String commitMessage) {
        File repoDir = getRepoDir(projectId);
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            throw new RuntimeException("Local workspace not found. Please clone the repository first.");
        }

        UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(token, "");

        try (Git git = Git.open(repoDir)) {
            // Stage all files
            git.add().addFilepattern(".").call();
            // Commit
            git.commit()
                    .setMessage(commitMessage)
                    .setAuthor("DeveloperEv AI", "ai@developerev.com")
                    .call();
            // Push
            git.push()
                    .setCredentialsProvider(credentials)
                    .call();
            log.info("Successfully pushed changes for project {}", projectId);
        } catch (GitAPIException | IOException e) {
            log.error("Failed to push changes for project {}: {}", projectId, e.getMessage(), e);
            throw new RuntimeException("Failed to push changes: " + e.getMessage());
        }
    }

    public File getRepoDir(Long projectId) {
        Path path = Paths.get(WORKSPACE_BASE, "project_" + projectId).toAbsolutePath().normalize();
        return path.toFile();
    }

    /**
     * Retrieves the current HEAD commit SHA of the local workspace.
     */
    public String getLatestCommitSha(Long projectId) {
        File repoDir = getRepoDir(projectId);
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            return null;
        }
        try (Git git = Git.open(repoDir)) {
            org.eclipse.jgit.lib.ObjectId head = git.getRepository().resolve("HEAD");
            return head != null ? head.getName() : null;
        } catch (Exception e) {
            log.error("Failed to retrieve HEAD commit for project {}: {}", projectId, e.getMessage());
            return null;
        }
    }
}
