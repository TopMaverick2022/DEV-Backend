package com.developerev.service;

import com.developerev.ai.exception.AiResponseParsingException;
import com.developerev.dto.LogAnalysisResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogAnalysisService {

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public LogAnalysisResponseDto analyzeLogFile(MultipartFile logFile) {
        log.info("Analyzing log file: {}", logFile.getOriginalFilename());

        if (logFile.isEmpty()) {
            throw new IllegalArgumentException("Log file is empty.");
        }

        StringBuilder logContent = new StringBuilder();
        int maxLines = 1000; // Limit to prevent payload going out of bounds
        int currentLine = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(logFile.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && currentLine < maxLines) {
                // Here we can easily do basic pre-filtering (e.g., only append lines with ERROR, WARN, Exception, etc.)
                // To maintain context we'll append the full lines up to a limit or if it contains interesting keywords.
                logContent.append(line).append("\n");
                currentLine++;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read the uploaded log file", e);
        }

        if (logContent.isEmpty()) {
            throw new IllegalArgumentException("Log file contains no readable text.");
        }

        String prompt = """
            You are a senior DevOps engineer and AI log analyzer.
            
            Analyze the following application log file and detect system problems automatically.
            
            Find patterns like:
            - Exceptions and stack traces
            - Timeout errors
            - Connection pool exhaustion
            - Slow SQL queries
            - Thread blocking
            - Repeated errors
            
            Return ONLY valid JSON.
            Do not include ```json or ``` markers.
            No explanations outside of the JSON.
            
            Format:
            {
              "issues": [
                {
                  "type": "Database",
                  "problem": "Connection pool exhaustion",
                  "severity": "HIGH",
                  "explanation": "Too many concurrent database requests",
                  "solution": "Increase pool size or optimize queries"
                }
              ]
            }
            
            LOG FILE CONTENT:
            """ + logContent.toString();

        log.info("Calling Gemini for Log Analysis. Length: {}", logContent.length());
        String geminiResponse = geminiClient.callGemini(prompt);

        String cleanedResponse = "";
        try {
            JsonNode root = objectMapper.readTree(geminiResponse);
            String textContent = root.path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            cleanedResponse = textContent
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            log.info("Gemini raw response: {}", cleanedResponse);

            JsonNode responseRoot = objectMapper.readTree(cleanedResponse);
            if (responseRoot.isArray()) {
                LogAnalysisResponseDto dto = new LogAnalysisResponseDto();
                java.util.List<LogAnalysisResponseDto.Issue> issues = new java.util.ArrayList<>();
                for (JsonNode issueNode : responseRoot) {
                    issues.add(objectMapper.treeToValue(issueNode, LogAnalysisResponseDto.Issue.class));
                }
                dto.setIssues(issues);
                return dto;
            }

            return objectMapper.readValue(cleanedResponse, LogAnalysisResponseDto.class);

        } catch (Exception e) {
            log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini Log Analysis response. Cleaned: {}", cleanedResponse, e);
            throw new AiResponseParsingException("Failed to parse Log Analysis response. Raw: " + (cleanedResponse.isEmpty() ? geminiResponse : cleanedResponse), e);
        }
    }
}
