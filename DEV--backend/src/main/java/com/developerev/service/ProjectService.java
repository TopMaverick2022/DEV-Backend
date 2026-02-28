package com.developerev.service;

import com.developerev.entity.User;
import com.developerev.model.Project;
import com.developerev.repository.ProjectRepository;
import com.developerev.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public Project createProject(String username, Project project) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        project.setOwner(user);
        return projectRepository.save(project);
    }

    public List<Project> getUserProjects(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return projectRepository.findByOwner(user);
    }

    public void deleteProject(Long projectId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Project project = projectRepository.findByIdAndOwner(projectId, user)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        projectRepository.delete(project);
    }
}
