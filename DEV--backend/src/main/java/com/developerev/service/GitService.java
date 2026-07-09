package com.developerev.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import com.developerev.repository.ProjectRepository;
import com.developerev.model.Project;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitService {

    private final ProjectRepository projectRepository;

    private static final String WORKSPACE_BASE = "workspaces";

    /**
     * Clones or pulls the latest changes from the repository into a local workspace.
     */
    public void syncRepository(String repoUrl, String token, Long projectId) {
        File repoDir = getRepoDir(projectId);
        UsernamePasswordCredentialsProvider credentials = (repoUrl != null && repoUrl.contains("gitlab"))
                ? new UsernamePasswordCredentialsProvider("oauth2", token)
                : new UsernamePasswordCredentialsProvider("x-oauth-token", token);

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

        Project project = projectRepository.findById(projectId).orElse(null);
        String repoUrl = project != null ? project.getGithubRepoUrl() : "";
        UsernamePasswordCredentialsProvider credentials = (repoUrl != null && repoUrl.contains("gitlab"))
                ? new UsernamePasswordCredentialsProvider("oauth2", token)
                : new UsernamePasswordCredentialsProvider("x-oauth-token", token);

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
        String dirName = "project_" + projectId;
        if (projectRepository != null) {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project != null && project.getName() != null) {
                dirName = project.getName().replaceAll("[\\\\/:*?\"<>|]", "_"); // Sanitize for filesystem
            }
        }
        Path path = Paths.get(WORKSPACE_BASE, dirName).toAbsolutePath().normalize();
        return path.toFile();
    }

    /**
     * Retrieves the current HEAD commit SHA of the local workspace.
     */
    public String getLocalCommitSha(Long projectId) {
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

    /**
     * Retrieves the latest commit SHA, preferring remote and falling back to local.
     */
    public String getLatestCommitSha(String repoUrl, Long projectId) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return getLocalCommitSha(projectId);
        }

        // Try to get the remote SHA first for the most accurate status
        try {
            log.debug("Checking remote HEAD for {}", repoUrl);
            // We don't hold tokens here, so this is an unauthenticated request.
            // It will work for public repos, but fail for private ones.
            Map<String, org.eclipse.jgit.lib.Ref> refs = Git.lsRemoteRepository()
                .setHeads(true)
                .setRemote(repoUrl)
                .setTimeout(5) // Don't hang the thread
                .callAsMap();
            
            // Find the ObjectId for the 'HEAD' ref
            org.eclipse.jgit.lib.Ref headRef = refs.get("HEAD");
            if (headRef != null && headRef.getObjectId() != null) {
                if (headRef.isSymbolic()) {
                    org.eclipse.jgit.lib.Ref concreteRef = refs.get(headRef.getTarget().getName());
                    if (concreteRef != null && concreteRef.getObjectId() != null) {
                        return concreteRef.getObjectId().getName();
                    }
                }
                return headRef.getObjectId().getName();
            }
        } catch (Exception e) {
            log.debug("Could not determine remote HEAD for {} ({}). This is expected for private repos or ZIP uploads.", repoUrl, e.getMessage());
        }

        // Fallback to local SHA if remote check fails
        return getLocalCommitSha(projectId);
    }


    /**
     * Retrieves commit activity grouped by date for the local workspace.
     */
    /**
     * Identifies files that changed (added or modified) between two commits.
     * This is used for incremental analysis of repositories.
     *
     * @param projectId project identifier
     * @param oldCommit older commit SHA-1
     * @param newCommit newer commit SHA-1
     * @return List of relative file paths that changed
     */
    public List<String> getChangedFiles(Long projectId, String oldCommit, String newCommit) {
        if (oldCommit == null || newCommit == null || oldCommit.equals(newCommit)) {
            return Collections.emptyList();
        }

        File repoDir = getRepoDir(projectId);
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            return Collections.emptyList();
        }

        List<String> changedFiles = new ArrayList<>();
        try (Git git = Git.open(repoDir)) {
            ObjectId oldHead = git.getRepository().resolve(oldCommit + "^{tree}");
            ObjectId newHead = git.getRepository().resolve(newCommit + "^{tree}");

            if (oldHead == null || newHead == null) {
                log.warn("Could not resolve trees for diff: {} vs {}", oldCommit, newCommit);
                return Collections.emptyList();
            }

            try (ObjectReader reader = git.getRepository().newObjectReader()) {
                CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
                oldTreeIter.reset(reader, oldHead);
                CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
                newTreeIter.reset(reader, newHead);

                List<DiffEntry> diffs = git.diff()
                        .setNewTree(newTreeIter)
                        .setOldTree(oldTreeIter)
                        .call();

                for (DiffEntry entry : diffs) {
                    // We only care about ADD and MODIFY. DELETE is handled by the regular scanner
                    // being absent, or we can optionally track it.
                    if (entry.getChangeType() == DiffEntry.ChangeType.ADD || entry.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                        changedFiles.add(entry.getNewPath());
                    }
                }
            }
            log.info("Found {} changed files between {} and {} for project {}", changedFiles.size(), oldCommit, newCommit, projectId);
        } catch (Exception e) {
            log.error("Failed to calculate diff for project {}: {}", projectId, e.getMessage(), e);
        }

        return changedFiles;
    }

    public List<Map<String, Object>> getCommitActivity(Long projectId) {
        File repoDir = getRepoDir(projectId);
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            return Collections.emptyList();
        }

        Map<String, Integer> commitCounts = new HashMap<>();
        try (Git git = Git.open(repoDir)) {
            Iterable<RevCommit> commits = git.log().all().call();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            for (RevCommit commit : commits) {
                Date commitDate = new Date(commit.getCommitTime() * 1000L);
                String dateStr = sdf.format(commitDate);
                commitCounts.put(dateStr, commitCounts.getOrDefault(dateStr, 0) + 1);
            }
        } catch (Exception e) {
            log.error("Failed to retrieve commit activity for project {}: {}", projectId, e.getMessage());
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : commitCounts.entrySet()) {
            Map<String, Object> map = new HashMap<>();
            map.put("date", entry.getKey());
            map.put("commits", entry.getValue());
            result.add(map);
        }

        // Sort by date ascending
        result.sort((a, b) -> ((String) a.get("date")).compareTo((String) b.get("date")));
        
        return result;
    }
}
