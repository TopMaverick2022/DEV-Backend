package com.developerev.service;

import com.developerev.dto.ProjectPlanResponseDto;
import com.developerev.repository.FeatureRepository;
import com.developerev.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AntiGravityService {

  private final GeminiClient geminiClient;
  private final FeatureRepository featureRepository;
  private final TaskRepository taskRepository;
  private final ObjectMapper objectMapper;

  public ProjectPlanResponseDto generateProjectPlan(Long projectId, String featureDescription) {

    String prompt = """
        You are a senior software architect.
        Return ONLY valid JSON.
        Do not wrap the response in markdown.
        Do not include ```json or ``` markers.
        No explanations. No markdown formatting.

        {
          "featureName": "",
          "complexity": "",
          "totalEstimatedHours": 0,
          "tasks": [
             {
               "title": "",
               "description": "",
               "type": "",
               "estimatedHours": 0,
               "priority": ""
             }
          ]
        }

        Feature:
        """ + featureDescription;

    String geminiResponse = geminiClient.callGemini(prompt);

    try {
      // Parse Gemini API JSON structure to extract the text
      JsonNode root = objectMapper.readTree(geminiResponse);
      String textContent = root.path("candidates").get(0)
          .path("content")
          .path("parts").get(0)
          .path("text").asText();

      // Clean the text from markdown formatting
      String cleanedResponse = textContent
          .replace("```json", "")
          .replace("```", "")
          .trim();

      return objectMapper.readValue(cleanedResponse, ProjectPlanResponseDto.class);
    } catch (Exception e) {
      log.error("Failed to parse Gemini response", e);
      throw new RuntimeException("Error parsing AI response: " + e.getMessage(), e);
    }
  }
}
