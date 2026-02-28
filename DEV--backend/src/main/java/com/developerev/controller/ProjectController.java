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
    public ResponseEntity<String> deleteProject(@PathVariable Long id, Authentication authentication) {
        String username = authentication.getName();
        projectService.deleteProject(id, username);
        return ResponseEntity.ok("Project deleted successfully");
    }
}
