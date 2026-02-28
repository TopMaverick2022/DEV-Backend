package com.developerev.controller;

import com.developerev.service.GitHubService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
public class GitHubController {

    private final GitHubService gitHubService;

    @GetMapping("/repo")
    public ResponseEntity<String> getRepositoryDetails(
            @RequestParam("owner") String owner,
            @RequestParam("repo") String repo,
            @RequestParam("token") String token) {

        String response = gitHubService.getRepositoryDetails(owner, repo, token);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/commits")
    public ResponseEntity<String> getRepositoryCommits(
            @RequestParam("owner") String owner,
            @RequestParam("repo") String repo,
            @RequestParam("token") String token) {

        String response = gitHubService.getRepositoryCommits(owner, repo, token);
        return ResponseEntity.ok(response);
    }
}
