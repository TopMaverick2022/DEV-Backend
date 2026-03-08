package com.developerev.service;

import com.developerev.dto.DependencyAiResponseDto;
import com.developerev.dto.ProjectPlanResponseDto;
import com.developerev.dto.SprintAiResponseDto;
import com.developerev.dto.SprintDetailDto;
import com.developerev.dto.TaskDependencyDto;
import com.developerev.model.Feature;
import com.developerev.model.Sprint;
import com.developerev.model.Task;
import com.developerev.model.TaskDependency;
import com.developerev.model.TaskStatus;
import com.developerev.repository.FeatureRepository;
import com.developerev.repository.SprintRepository;
import com.developerev.repository.TaskDependencyRepository;
import com.developerev.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AntiGravityService {

  private final GeminiClient geminiClient;
  private final FeatureRepository featureRepository;
  private final TaskRepository taskRepository;
  private final SprintRepository sprintRepository;
  private final TaskDependencyRepository taskDependencyRepository;
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
          task.setStatus(TaskStatus.TODO);

          taskRepository.save(task);
        }
      }

      return responseDto;
    } catch (Exception e) {
      log.error("Failed to parse Gemini response", e);
      throw new RuntimeException("Error parsing AI response: " + e.getMessage(), e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // AI Sprint Generator
  // ─────────────────────────────────────────────────────────────────────────────

  public List<SprintDetailDto> generateSprints(Long featureId) {

    // 1. Load feature
    Feature feature = featureRepository.findById(featureId)
        .orElseThrow(() -> new RuntimeException("Feature not found with id: " + featureId));

    // 2. Load tasks — build both the prompt list and an ID→Task lookup map
    List<Task> tasks = taskRepository.findByFeatureId(featureId);
    if (tasks.isEmpty()) {
      throw new RuntimeException("No tasks found for feature id: " + featureId + ". Generate a project plan first.");
    }

    java.util.Map<Long, Task> taskMap = new java.util.HashMap<>();
    StringBuilder taskList = new StringBuilder();
    for (Task task : tasks) {
      taskMap.put(task.getId(), task);
      taskList.append("- taskId: ").append(task.getId())
          .append(", title: \"").append(task.getTitle()).append("\"")
          .append(", estimatedHours: ").append(task.getEstimatedHours())
          .append(", type: ").append(task.getType())
          .append(", priority: ").append(task.getPriority())
          .append("\n");
    }

    // 3. Build prompt (ID-based — AI returns taskId references, no title parsing
    // needed)
    String prompt = """
        You are a senior engineering manager and agile sprint planner.

        Your job is to organize development tasks into logical engineering sprints.

        Rules:
        1. Each sprint should contain approximately 35–45 hours of work.
        2. Backend foundation tasks should appear before frontend tasks.
        3. Database and infrastructure tasks must be completed first.
        4. Testing and deployment tasks should appear in the final sprints.
        5. Maintain logical engineering dependencies.

        Return ONLY valid JSON.
        Do not include explanations.
        Do not include markdown.
        Return JSON only.

        Response format:
        {
          "sprints": [
            {
              "name": "Sprint name",
              "sprintNumber": 1,
              "tasks": [
                { "taskId": 1 }
              ]
            }
          ]
        }

        Tasks for feature "%s":
        %s
        """.formatted(feature.getName(), taskList);

    log.info("Calling Gemini for sprint generation of feature: {}", feature.getName());
    String geminiResponse = geminiClient.callGemini(prompt);

    try {
      // 4. Extract text content from Gemini response envelope
      JsonNode root = objectMapper.readTree(geminiResponse);
      String textContent = root.path("candidates").get(0)
          .path("content")
          .path("parts").get(0)
          .path("text").asText();

      String cleanedResponse = textContent
          .replace("```json", "")
          .replace("```", "")
          .trim();

      log.debug("Gemini sprint response (cleaned): {}", cleanedResponse);

      // 5. Parse Gemini response into DTO
      SprintAiResponseDto aiResponse = objectMapper.readValue(cleanedResponse, SprintAiResponseDto.class);

      List<SprintDetailDto> result = new ArrayList<>();

      // 6. Persist each sprint and assign tasks by ID (exact, reliable lookup)
      for (SprintAiResponseDto.SprintItem sprintItem : aiResponse.getSprints()) {

        int sprintNumber = sprintItem.getSprintNumber() > 0
            ? sprintItem.getSprintNumber()
            : result.size() + 1;

        Sprint sprint = Sprint.builder()
            .featureId(featureId)
            .name(sprintItem.getName())
            .sprintNumber(sprintNumber)
            .build();

        sprint = sprintRepository.save(sprint);
        log.info("Saved sprint: {} (number {})", sprint.getName(), sprintNumber);

        List<Task> assignedTasks = new ArrayList<>();

        if (sprintItem.getTasks() != null) {
          for (SprintAiResponseDto.TaskRef ref : sprintItem.getTasks()) {
            Task task = taskMap.get(ref.getTaskId());
            if (task != null) {
              task.setSprintId(sprint.getId());
              taskRepository.save(task);
              assignedTasks.add(task);
            } else {
              log.warn("AI referenced unknown taskId {} in sprint '{}' — skipping", ref.getTaskId(), sprint.getName());
            }
          }
        }

        result.add(new SprintDetailDto(sprint.getId(), sprint.getName(), sprintNumber, assignedTasks));
      }

      log.info("Generated {} sprints for feature {}", result.size(), featureId);
      return result;

    } catch (Exception e) {
      log.error("Failed to parse Gemini sprint response", e);
      throw new RuntimeException("Error generating sprints: " + e.getMessage(), e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // AI Task Dependency Engine
  // ─────────────────────────────────────────────────────────────────────────────

  public List<TaskDependencyDto> detectDependencies(Long featureId) {

    // 1. Load tasks and build id→Task lookup map
    List<Task> tasks = taskRepository.findByFeatureId(featureId);
    if (tasks.isEmpty()) {
      throw new RuntimeException("No tasks found for feature id: " + featureId + ". Generate a project plan first.");
    }

    Map<Long, Task> taskMap = new java.util.HashMap<>();
    StringBuilder taskList = new StringBuilder();
    for (Task task : tasks) {
      taskMap.put(task.getId(), task);
      taskList.append(task.getId())
          .append(" - ").append(task.getTitle())
          .append("\n");
    }

    // 2. Build the Gemini prompt
    String prompt = """
        You are a senior software architect and agile planner.

        Analyze the following development tasks and determine logical dependencies between them.

        A dependency means one task must be completed before another can start.

        Rules:
        - Database tasks come before backend APIs
        - Backend APIs come before frontend integration
        - Infrastructure tasks come before deployment
        - Testing tasks usually depend on implementation tasks
        - Use only the taskId values listed below
        - Do not create circular dependencies
        - Only include dependencies that are strictly required
        - If a task has no dependencies, do not include it

        Return ONLY valid JSON. No markdown. No explanations.

        Response format:
        {
          "dependencies": [
            {
              "taskId": 3,
              "dependsOn": 1
            }
          ]
        }

        Where:
        taskId = the dependent task (cannot start yet)
        dependsOn = the prerequisite task (must finish first)

        Tasks:
        %s
        """.formatted(taskList);

    log.info("Calling Gemini for dependency detection on feature: {}", featureId);
    String geminiResponse = geminiClient.callGemini(prompt);

    try {
      // 3. Extract text from Gemini envelope
      JsonNode root = objectMapper.readTree(geminiResponse);
      String textContent = root.path("candidates").get(0)
          .path("content")
          .path("parts").get(0)
          .path("text").asText();

      String cleanedResponse = textContent
          .replace("```json", "")
          .replace("```", "")
          .trim();

      log.debug("Gemini dependency response (cleaned): {}", cleanedResponse);

      // 4. Parse AI response
      DependencyAiResponseDto aiResponse = objectMapper.readValue(cleanedResponse, DependencyAiResponseDto.class);

      List<TaskDependencyDto> result = new ArrayList<>();

      if (aiResponse.getDependencies() != null) {
        for (DependencyAiResponseDto.DependencyItem item : aiResponse.getDependencies()) {

          Task dependentTask = taskMap.get(item.getTaskId());
          Task prerequisiteTask = taskMap.get(item.getDependsOn());

          if (dependentTask == null) {
            log.warn("AI referenced unknown dependent taskId {} — skipping", item.getTaskId());
            continue;
          }
          if (prerequisiteTask == null) {
            log.warn("AI referenced unknown prerequisite taskId {} — skipping", item.getDependsOn());
            continue;
          }

          // 5. Persist the dependency
          TaskDependency dependency = TaskDependency.builder()
              .dependentTask(dependentTask)
              .prerequisiteTask(prerequisiteTask)
              .build();
          taskDependencyRepository.save(dependency);

          result.add(new TaskDependencyDto(
              dependentTask.getId(),
              dependentTask.getTitle(),
              prerequisiteTask.getId(),
              prerequisiteTask.getTitle()));

          log.info("Saved dependency: '{}' depends on '{}'",
              dependentTask.getTitle(), prerequisiteTask.getTitle());
        }
      }

      log.info("Detected {} dependencies for feature {}", result.size(), featureId);
      return result;

    } catch (Exception e) {
      log.error("Failed to parse Gemini dependency response", e);
      throw new RuntimeException("Error detecting dependencies: " + e.getMessage(), e);
    }
  }

}
