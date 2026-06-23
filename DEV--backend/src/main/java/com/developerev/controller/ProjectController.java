package com.developerev.controller;

import com.developerev.model.Project;
import com.developerev.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final com.developerev.service.AntiGravityService antiGravityService;

    @PostMapping
    public ResponseEntity<Project> createProject(@RequestBody Project project, Authentication authentication) {
        String username = authentication.getName();
        Project savedProject = projectService.createProject(username, project);
        return ResponseEntity.ok(savedProject);
    }

    @GetMapping
    public ResponseEntity<List<Project>> getMyProjects(Authentication authentication) {
        String username = authentication.getName();
        return ResponseEntity.ok(projectService.getUserProjects(username));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteProject(@PathVariable("id") Long id, Authentication authentication) {
        String username = authentication.getName();
        projectService.deleteProject(id, username);
        return ResponseEntity.ok("Project deleted successfully");
    }

    @PutMapping("/{id}/settings")
    public ResponseEntity<Project> updateProjectSettings(
            @PathVariable("id") Long id,
            @RequestBody Project updatedProject,
            Authentication authentication) {
        String username = authentication.getName();
        return ResponseEntity.ok(projectService.updateProjectSettings(id, username, updatedProject));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<String> addProjectMember(
            @PathVariable("id") Long id,
            @RequestBody com.developerev.dto.AddProjectMemberRequestDto request,
            Authentication authentication) {
        String username = authentication.getName();
        projectService.addProjectMember(id, username, request);
        return ResponseEntity.ok("Project member added successfully");
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<String>> getProjectMembers(
            @PathVariable("id") Long id,
            Authentication authentication) {
        String username = authentication.getName();
        return ResponseEntity.ok(projectService.getProjectMembers(id, username));
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<com.developerev.dto.ProjectStatsDto> getProjectStats(
            @PathVariable("id") Long id,
            Authentication authentication) {
        String username = authentication.getName();
        return ResponseEntity.ok(projectService.getProjectStats(id, username));
    }

    @PostMapping("/{id}/analyze-business-context")
    public ResponseEntity<java.util.Map<String, String>> analyzeBusinessContext(
            @PathVariable("id") Long id,
            Authentication authentication) {
        String context = antiGravityService.analyzeAndStoreProjectContext(id);
        return ResponseEntity.ok(java.util.Map.of("aiBusinessContext", context));
    }
}
