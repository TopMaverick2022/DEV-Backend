package com.developerev.controller;

import com.developerev.service.GitLabService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gitlab")
@RequiredArgsConstructor
public class GitLabController {

    private final GitLabService gitLabService;

    @GetMapping("/repo")
    public ResponseEntity<String> getRepositoryDetails(
            @RequestParam("projectPath") String projectPath,
            @RequestParam(value = "token", required = false, defaultValue = "") String token,
            @RequestParam(value = "host", required = false, defaultValue = "gitlab.com") String host,
            @RequestParam(value = "protocol", required = false, defaultValue = "https") String protocol) {

        String response = gitLabService.getRepositoryDetails(projectPath, token, host, protocol);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/commits")
    public ResponseEntity<String> getRepositoryCommits(
            @RequestParam("projectPath") String projectPath,
            @RequestParam(value = "token", required = false, defaultValue = "") String token,
            @RequestParam(value = "host", required = false, defaultValue = "gitlab.com") String host,
            @RequestParam(value = "protocol", required = false, defaultValue = "https") String protocol) {

        String response = gitLabService.getRepositoryCommits(projectPath, token, host, protocol);
        return ResponseEntity.ok(response);
    }
}
