package com.developerev.service;

import com.developerev.dto.AddProjectMemberRequestDto;
import com.developerev.entity.User;
import com.developerev.model.Project;
import com.developerev.model.ProjectMember;
import com.developerev.repository.ActivityLogRepository;
import com.developerev.repository.ProjectMemberRepository;
import com.developerev.repository.ProjectRepository;
import com.developerev.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import com.developerev.repository.CodeFileRepository;
import com.developerev.repository.CodeProjectRepository;
import com.developerev.repository.CodeReviewRepository;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;
    private final ActivityLogRepository activityLogRepository;
    
    // AI Integration Fields
    private final CodeProjectRepository codeProjectRepository;
    private final CodeFileRepository codeFileRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final GitService gitService;

    public Project createProject(String username, Project project) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        project.setOwner(user);
        Project savedProject = projectRepository.save(project);

        // Add owner as a project member with ADMIN role
        ProjectMember member = ProjectMember.builder()
                .project(savedProject)
                .user(user)
                .role("ADMIN")
                .build();
        projectMemberRepository.save(member);

        activityLogService.logActivity(user, savedProject, "Created Project", "Project created successfully");
        return savedProject;
    }

    public List<Project> getUserProjects(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Fetch all projects where the user is a member
        List<Project> memberProjects = projectMemberRepository.findByUserId(user.getId()).stream()
                .map(ProjectMember::getProject)
                .collect(Collectors.toList());

        // Fetch all projects where the user is the owner
        List<Project> ownedProjects = projectRepository.findByOwner(user);

        // Combine and ensure distinctness
        java.util.Set<Project> result = new java.util.HashSet<>(memberProjects);
        result.addAll(ownedProjects);
        return new java.util.ArrayList<>(result);
    }

    @Transactional
    public Project updateProjectSettings(Long projectId, String username, Project updatedProject) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project with ID " + projectId + " not found"));

        // Verify user is an ADMIN (or owner/orphan fallback)
        ProjectMember member = getOrAssignAdminMember(project, user);
        if (!"ADMIN".equals(member.getRole())) {
            throw new RuntimeException("Only admins can update project settings");
        }

        project.setDescription(updatedProject.getDescription());
        project.setGithubRepoUrl(updatedProject.getGithubRepoUrl());
        project.setName(updatedProject.getName());
        project.setLanguage(updatedProject.getLanguage());
        project.setLanguageVersion(updatedProject.getLanguageVersion());
        project.setFramework(updatedProject.getFramework());
        project.setFrameworkVersion(updatedProject.getFrameworkVersion());
        project.setDatabaseName(updatedProject.getDatabaseName());
        project.setDatabaseVersion(updatedProject.getDatabaseVersion());
        project.setDependencies(updatedProject.getDependencies());
        Project saved = projectRepository.save(project);

        activityLogService.logActivity(user, saved, "Updated Settings", "Updated project metadata");
        return saved;
    }

    @Transactional
    public void addProjectMember(Long projectId, String username, AddProjectMemberRequestDto request) {
        User admin = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project with ID " + projectId + " not found"));

        ProjectMember adminMember = getOrAssignAdminMember(project, admin);

        if (!"ADMIN".equals(adminMember.getRole())) {
            throw new RuntimeException("Only admins can add members");
        }

        User targetUser = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Target user not found"));

        if (projectMemberRepository.findByProjectIdAndUserId(projectId, targetUser.getId()).isPresent()) {
            throw new RuntimeException("User is already a project member");
        }

        ProjectMember newMember = ProjectMember.builder()
                .project(project)
                .user(targetUser)
                .role(request.getRole())
                .build();

        projectMemberRepository.save(newMember);
        activityLogService.logActivity(admin, project, "Added Member", "Added " + request.getEmail() + " as " + request.getRole());
    }

    public List<String> getProjectMembers(Long projectId, String username) {
        User requester = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project with ID " + projectId + " not found"));

        getOrAssignAdminMember(project, requester);

        return projectMemberRepository.findByProjectId(projectId).stream()
                .map(member -> member.getUser().getUsername() + " (" + member.getRole() + ")")
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteProject(Long projectId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Project project = projectRepository.findByIdAndOwner(projectId, user)
                .orElseThrow(() -> new RuntimeException("Project not found or you are not the owner"));

        // Delete child records first to satisfy FK constraints
        activityLogRepository.deleteByProjectId(projectId);
        projectMemberRepository.deleteByProjectId(projectId);

        projectRepository.delete(project);
    }

    private ProjectMember getOrAssignAdminMember(Project project, User user) {
        return projectMemberRepository.findByProjectIdAndUserId(project.getId(), user.getId())
                .orElseGet(() -> {
                    boolean isOwner = project.getOwner() != null && project.getOwner().getId().equals(user.getId());
                    boolean isOrphan = project.getOwner() == null;
                    
                    if (isOwner || isOrphan) {
                        if (isOrphan) {
                            project.setOwner(user);
                            projectRepository.save(project);
                        }
                        ProjectMember newOwnerMember = ProjectMember.builder()
                                .project(project)
                                .user(user)
                                .role("ADMIN")
                                .build();
                        return projectMemberRepository.save(newOwnerMember);
                    }
                    throw new RuntimeException("Not a member of this project");
                });
    }

    public com.developerev.dto.ProjectStatsDto getProjectStats(Long projectId, String username) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));
        getOrAssignAdminMember(project, userRepository.findByUsername(username).orElseThrow());

        int bugs = 0, security = 0, perf = 0;
        int filesAnalyzed = 0;

        com.developerev.model.CodeProject cp = codeProjectRepository.findTopByLinkedProjectIdOrderByIdDesc(project.getId());
        if (cp == null) {
            // Fallback to name for legacy data
            cp = codeProjectRepository.findTopByNameOrderByIdDesc(project.getName());
        }
        if (cp != null) {
            List<com.developerev.model.CodeFile> files = codeFileRepository.findByProjectId(cp.getId());
            filesAnalyzed = files.size();
            for (com.developerev.model.CodeFile file : files) {
                List<com.developerev.model.CodeReview> reviews = codeReviewRepository.findByFileId(file.getId());
                for (com.developerev.model.CodeReview r : reviews) {
                    bugs += r.getBugCount();
                    security += r.getSecurityCount();
                    perf += r.getPerformanceCount();
                }
            }
        }

        // Calculate health score: use an inverse proportion to prevent exactly 0% while remaining accurate
        double penalty = (bugs * 2.0) + (security * 5.0) + (perf * 1.0);
        double healthScore = 100.0 * (100.0 / (100.0 + penalty));
        healthScore = Math.round(healthScore * 10.0) / 10.0;
        if (filesAnalyzed == 0) healthScore = 0.0; // Unknown health if no analysis

        // Estimate tech debt (1 bug = 1h, security = 3h, perf = 0.5h)
        double hours = bugs * 1.0 + security * 3.0 + perf * 0.5;
        String techDebt = hours > 0 ? String.format("%.1fh", hours) : "0h";

        // Determine Sync Status
        String syncStatus = "UNKNOWN";
        if (project.getGithubRepoUrl() != null && !project.getGithubRepoUrl().isEmpty()) {
            String remoteSha = gitService.getLatestCommitSha(project.getGithubRepoUrl(), projectId);
            String localSha = gitService.getLocalCommitSha(projectId);
            String analyzedSha = project.getLastAnalyzedCommit();

            if (remoteSha == null) {
                syncStatus = "UNKNOWN";
            } else if (analyzedSha == null) {
                // If it's never been analyzed, check if it's at least been pulled
                syncStatus = (localSha != null && localSha.equalsIgnoreCase(remoteSha)) ? "NEEDS_ANALYSIS" : "NEEDS_PULL";
            } else if (!analyzedSha.equalsIgnoreCase(remoteSha)) {
                // If analyzed commit is NOT the remote HEAD, check if we've at least pulled the remote HEAD
                syncStatus = (localSha != null && localSha.equalsIgnoreCase(remoteSha)) ? "NEEDS_ANALYSIS" : "NEEDS_PULL";
            } else {
                syncStatus = "SYNCED";
            }
        }

        return com.developerev.dto.ProjectStatsDto.builder()
                .healthScore(healthScore)
                .totalFilesAnalyzed(filesAnalyzed)
                .totalBugs(bugs)
                .totalSecurityIssues(security)
                .totalPerformanceIssues(perf)
                .techDebtEstimate(techDebt)
                .syncStatus(syncStatus)
                .build();
    }

    /**
     * Clears the lastAnalyzedCommit field for a project.
     * Called after a ZIP upload so that the incremental analysis logic
     * does not skip files in the newly uploaded workspace.
     */
    @Transactional
    public void resetLastAnalyzedCommit(Long projectId) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setLastAnalyzedCommit(null);
            projectRepository.save(p);
        });
    }
}

