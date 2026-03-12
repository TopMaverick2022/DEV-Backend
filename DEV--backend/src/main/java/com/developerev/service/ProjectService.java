package com.developerev.service;

import com.developerev.dto.AddProjectMemberRequestDto;
import com.developerev.entity.User;
import com.developerev.model.Project;
import com.developerev.model.ProjectMember;
import com.developerev.repository.ProjectMemberRepository;
import com.developerev.repository.ProjectRepository;
import com.developerev.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;

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

    public void deleteProject(Long projectId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Project project = projectRepository.findByIdAndOwner(projectId, user)
                .orElseThrow(() -> new RuntimeException("Project not found or you are not the owner"));

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
}
