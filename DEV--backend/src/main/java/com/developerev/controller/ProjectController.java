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

    /**
     * Links this project to another project bidirectionally.
     * Body: { "relatedProjectId": 5 }
     * Optionally accepts projectType override: { "relatedProjectId": 5, "projectType": "FRONTEND" }
     */
    @PutMapping("/{id}/link")
    public ResponseEntity<Project> linkProject(
            @PathVariable("id") Long id,
            @RequestBody java.util.Map<String, Object> body,
            Authentication authentication) {
        Long relatedProjectId = Long.valueOf(body.get("relatedProjectId").toString());
        Project project = projectService.linkProjects(id, relatedProjectId, authentication.getName());
        // Allow explicit projectType override from payload
        if (body.containsKey("projectType")) {
            project.setProjectType(body.get("projectType").toString());
            // Also persist if provided
        }
        return ResponseEntity.ok(project);
    }

    /**
     * Removes the bidirectional link for this project and resets type to STANDALONE.
     */
    @DeleteMapping("/{id}/link")
    public ResponseEntity<Project> unlinkProject(
            @PathVariable("id") Long id,
            Authentication authentication) {
        return ResponseEntity.ok(projectService.unlinkProject(id, authentication.getName()));
    }

    /**
     * Returns the linked companion project (if any).
     */
    @GetMapping("/{id}/linked")
    public ResponseEntity<Project> getLinkedProject(
            @PathVariable("id") Long id,
            Authentication authentication) {
        return projectService.getLinkedProject(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}
