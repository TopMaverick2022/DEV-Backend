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

      ProjectPlanResponseDto responseDto = objectMapper.readValue(cleanedResponse, ProjectPlanResponseDto.class);

      // Save Feature to database
      com.developerev.model.Feature feature = new com.developerev.model.Feature();
      feature.setProjectId(projectId);
      feature.setName(responseDto.getFeatureName());
      feature.setDescription(featureDescription);
      feature.setComplexity(responseDto.getComplexity());
      feature.setTotalEstimatedHours(responseDto.getTotalEstimatedHours());

      feature = featureRepository.save(feature);

      // Save related Tasks
      if (responseDto.getTasks() != null) {
        for (ProjectPlanResponseDto.TaskDto taskDto : responseDto.getTasks()) {
          com.developerev.model.Task task = new com.developerev.model.Task();
          task.setProjectId(projectId);
          task.setFeatureId(feature.getId());
          task.setTitle(taskDto.getTitle());
          task.setDescription(taskDto.getDescription());
          task.setType(taskDto.getType());
          task.setEstimatedHours(taskDto.getEstimatedHours());
          task.setPriority(taskDto.getPriority());
          task.setStatus("TODO");

          taskRepository.save(task);
        }
      }

      return responseDto;
    } catch (Exception e) {
      log.error("Failed to parse Gemini response", e);
      throw new RuntimeException("Error parsing AI response: " + e.getMessage(), e);
    }
  }
}
