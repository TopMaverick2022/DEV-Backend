package com.developerev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public String getRepositoryDetails(String projectPath, String token) {
        return getRepositoryDetails(projectPath, token, "gitlab.com", "https");
    }

    public String getRepositoryDetails(String projectPath, String token, String host, String protocol) {
        try {
            String encodedPath = URLEncoder.encode(projectPath.trim(), StandardCharsets.UTF_8.name());
            String cleanProtocol = (protocol != null && !protocol.isBlank()) ? protocol.replaceAll("[:/]", "") : "https";
            String cleanHost = (host != null && !host.isBlank()) ? host : "gitlab.com";
            String url = String.format("%s://%s/api/v4/projects/%s", cleanProtocol, cleanHost, encodedPath);

            HttpHeaders headers = createHeaders(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.info("Fetching GitLab repository details from: {}", url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String responseBody = response.getBody();

            // Deserialize and normalize
            Map<String, Object> gitlabRepo = objectMapper.readValue(responseBody, Map.class);
            Map<String, Object> normalized = new HashMap<>();
            normalized.put("full_name", gitlabRepo.get("name_with_namespace"));
            normalized.put("description", gitlabRepo.get("description"));
            normalized.put("stargazers_count", gitlabRepo.get("star_count"));
            normalized.put("forks_count", gitlabRepo.get("forks_count"));
            normalized.put("open_issues_count", gitlabRepo.get("open_issues_count"));
            normalized.put("watchers_count", gitlabRepo.get("star_count")); // GitLab doesn't return watchers separately easily
            normalized.put("language", "GitLab");
            normalized.put("default_branch", gitlabRepo.get("default_branch"));
            normalized.put("html_url", gitlabRepo.get("web_url"));

            String visibility = (String) gitlabRepo.get("visibility");
            normalized.put("private", visibility != null && !visibility.equalsIgnoreCase("public"));

            return objectMapper.writeValueAsString(normalized);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("GitLab API returned error code {} for projectPath {}: {}", e.getStatusCode(), projectPath, e.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(e.getStatusCode(), e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Failed to fetch/parse GitLab repo details for projectPath {}: {}", projectPath, e.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch GitLab repo details: " + e.getMessage(), e);
        }
    }

    public String getRepositoryCommits(String projectPath, String token) {
        return getRepositoryCommits(projectPath, token, "gitlab.com", "https");
    }

    public String getRepositoryCommits(String projectPath, String token, String host, String protocol) {
        try {
            String encodedPath = URLEncoder.encode(projectPath.trim(), StandardCharsets.UTF_8.name());
            String cleanProtocol = (protocol != null && !protocol.isBlank()) ? protocol.replaceAll("[:/]", "") : "https";
            String cleanHost = (host != null && !host.isBlank()) ? host : "gitlab.com";
            String url = String.format("%s://%s/api/v4/projects/%s/repository/commits", cleanProtocol, cleanHost, encodedPath);

            HttpHeaders headers = createHeaders(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.info("Fetching GitLab repository commits from: {}", url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String responseBody = response.getBody();

            // Deserialize and normalize list of commits
            List<Map<String, Object>> gitlabCommits = objectMapper.readValue(responseBody, List.class);
            List<Map<String, Object>> normalizedList = new ArrayList<>();

            for (Map<String, Object> glCommit : gitlabCommits) {
                Map<String, Object> normalized = new HashMap<>();
                normalized.put("sha", glCommit.get("id"));
                normalized.put("html_url", glCommit.get("web_url"));

                Map<String, Object> commitObj = new HashMap<>();
                commitObj.put("message", glCommit.get("message"));

                Map<String, Object> authorObj = new HashMap<>();
                authorObj.put("name", glCommit.get("author_name"));
                authorObj.put("date", glCommit.get("authored_date"));

                commitObj.put("author", authorObj);
                normalized.put("commit", commitObj);

                normalizedList.add(normalized);
            }

            return objectMapper.writeValueAsString(normalizedList);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("GitLab API returned error code {} for projectPath {}: {}", e.getStatusCode(), projectPath, e.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(e.getStatusCode(), e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Failed to fetch/parse GitLab commits for projectPath {}: {}", projectPath, e.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch GitLab commits: " + e.getMessage(), e);
        }
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isBlank()) {
            // GitLab standard personal access token header
            headers.set("PRIVATE-TOKEN", token);
        }
        return headers;
    }
}
