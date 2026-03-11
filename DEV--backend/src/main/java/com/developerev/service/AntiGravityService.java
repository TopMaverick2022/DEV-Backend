package com.developerev.service;

import com.developerev.ai.exception.AiResponseParsingException;
import com.developerev.ai.exception.UnexpectedAiException;
import com.developerev.dto.ArchitectureResponseDto;
import com.developerev.dto.CriticalPathResponseDto;
import com.developerev.dto.DependencyAiResponseDto;
import com.developerev.dto.ProjectPlanResponseDto;
import com.developerev.dto.SprintAiResponseDto;
import com.developerev.dto.SprintDetailDto;
import com.developerev.dto.TaskDependencyDto;
import com.developerev.model.ArchitecturePlan;
import com.developerev.model.Feature;
import com.developerev.model.Sprint;
import com.developerev.model.Task;
import com.developerev.model.TaskDependency;
import com.developerev.model.TaskStatus;
import com.developerev.repository.ArchitecturePlanRepository;
import com.developerev.repository.FeatureRepository;
import com.developerev.repository.SprintRepository;
import com.developerev.repository.TaskDependencyRepository;
import com.developerev.repository.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
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
  private final ArchitecturePlanRepository architecturePlanRepository;
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
    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini project-plan response", e);
      throw new AiResponseParsingException(
          "JSON parse failure in generateProjectPlan. Raw response excerpt: "
              + (geminiResponse != null ? geminiResponse.substring(0, Math.min(geminiResponse.length(), 200)) : "null"),
          e);
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in generateProjectPlan", e);
      throw new UnexpectedAiException("Unexpected error in generateProjectPlan: " + e.getMessage(), e);
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

    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini sprint response", e);
      throw new AiResponseParsingException(
          "JSON parse failure in generateSprints for featureId=" + featureId
              + ". Raw response excerpt: "
              + (geminiResponse != null ? geminiResponse.substring(0, Math.min(geminiResponse.length(), 200)) : "null"),
          e);
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in generateSprints", e);
      throw new UnexpectedAiException(
          "Unexpected error in generateSprints for featureId=" + featureId + ": " + e.getMessage(), e);
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

    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini dependency response", e);
      throw new AiResponseParsingException(
          "JSON parse failure in detectDependencies for featureId=" + featureId
              + ". Raw response excerpt: "
              + (geminiResponse != null ? geminiResponse.substring(0, Math.min(geminiResponse.length(), 200)) : "null"),
          e);
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in detectDependencies", e);
      throw new UnexpectedAiException(
          "Unexpected error in detectDependencies for featureId=" + featureId + ": " + e.getMessage(), e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Critical Path Engine
  // ─────────────────────────────────────────────────────────────────────────────

  public CriticalPathResponseDto computeCriticalPath(Long featureId) {

    // 1. Load tasks
    List<Task> tasks = taskRepository.findByFeatureId(featureId);
    if (tasks.isEmpty()) {
      throw new RuntimeException("No tasks found for feature id: " + featureId);
    }

    Map<Long, Task> taskMap = new java.util.HashMap<>();
    for (Task t : tasks)
      taskMap.put(t.getId(), t);

    // 2. Load saved dependencies
    List<com.developerev.model.TaskDependency> deps = taskDependencyRepository.findByDependentTask_FeatureId(featureId);

    // 3. Build adjacency list: prerequisite → list of dependents
    // and in-degree map for Kahn's topological sort
    Map<Long, List<Long>> dependents = new java.util.HashMap<>();
    Map<Long, Integer> inDegree = new java.util.HashMap<>();
    for (Task t : tasks) {
      dependents.put(t.getId(), new ArrayList<>());
      inDegree.put(t.getId(), 0);
    }
    for (com.developerev.model.TaskDependency dep : deps) {
      Long prereqId = dep.getPrerequisiteTask().getId();
      Long depId = dep.getDependentTask().getId();
      dependents.get(prereqId).add(depId);
      inDegree.merge(depId, 1, (a, b) -> a + b);
    }

    // 4. Kahn's algorithm — track earliest finish time (EFT) and parent per task
    // EFT(task) = max(EFT of all prerequisites) + task.estimatedHours
    Map<Long, Integer> eft = new java.util.HashMap<>();
    Map<Long, Long> parent = new java.util.HashMap<>();
    java.util.Queue<Long> queue = new java.util.LinkedList<>();

    for (Task t : tasks) {
      int hours = t.getEstimatedHours() != null ? t.getEstimatedHours() : 0;
      eft.put(t.getId(), hours); // initial EFT = own hours (no prereq yet)
      parent.put(t.getId(), null);
      if (inDegree.get(t.getId()) == 0)
        queue.add(t.getId());
    }

    while (!queue.isEmpty()) {
      Long curr = queue.poll();
      for (Long nextId : dependents.get(curr)) {
        int candidate = eft.get(curr) +
            (taskMap.get(nextId).getEstimatedHours() != null
                ? taskMap.get(nextId).getEstimatedHours()
                : 0);
        if (candidate > eft.get(nextId)) {
          eft.put(nextId, candidate);
          parent.put(nextId, curr);
        }
        inDegree.merge(nextId, -1, (a, b) -> a + b);
        if (inDegree.get(nextId) == 0)
          queue.add(nextId);
      }
    }

    // 5. Find the task with the highest EFT — that is the end of the critical path
    Long endTaskId = tasks.stream()
        .map(Task::getId)
        .max(java.util.Comparator.comparingInt(eft::get))
        .orElseThrow();
    int criticalPathHours = eft.get(endTaskId);

    // 6. Walk parent pointers back to reconstruct the path, then reverse it
    java.util.Deque<String> path = new java.util.ArrayDeque<>();
    Long cur = endTaskId;
    while (cur != null) {
      path.addFirst(taskMap.get(cur).getTitle());
      cur = parent.get(cur);
    }

    log.info("Critical path for feature {}: {} hours, {} tasks",
        featureId, criticalPathHours, path.size());

    return new CriticalPathResponseDto(criticalPathHours, new ArrayList<>(path));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // AI Architecture Generator
  // ─────────────────────────────────────────────────────────────────────────────

  public ArchitectureResponseDto generateArchitecture(String projectIdea) {

    // 1. Build the Gemini prompt
    String prompt = """
        You are a senior software architect.

        Design a scalable system architecture for the following project idea.

        Include:
        1. Microservices (name, description, database)
        2. REST APIs (method, endpoint, description)
        3. Event flows (name, producer, consumer)
        4. External integrations where applicable

        Return ONLY valid JSON. No markdown. No explanations.

        Response format:
        {
          "services": [
            {
              "name": "",
              "description": "",
              "database": ""
            }
          ],
          "apis": [
            {
              "method": "",
              "endpoint": "",
              "description": ""
            }
          ],
          "events": [
            {
              "name": "",
              "producer": "",
              "consumer": ""
            }
          ]
        }

        Project idea: %s
        """.formatted(projectIdea);

    log.info("Calling Gemini for architecture generation: {}", projectIdea);
    String geminiResponse = geminiClient.callGemini(prompt);

    try {
      // 2. Extract text from Gemini response envelope
      JsonNode root = objectMapper.readTree(geminiResponse);
      String textContent = root.path("candidates").get(0)
          .path("content")
          .path("parts").get(0)
          .path("text").asText();

      String cleanedResponse = textContent
          .replace("```json", "")
          .replace("```", "")
          .trim();

      log.debug("Gemini architecture response (cleaned): {}", cleanedResponse);

      // 3. Parse AI response into DTO
      ArchitectureResponseDto responseDto = objectMapper.readValue(cleanedResponse, ArchitectureResponseDto.class);

      // 4. Persist the architecture plan to the database
      ArchitecturePlan plan = ArchitecturePlan.builder()
          .projectIdea(projectIdea)
          .architectureJson(cleanedResponse)
          .build();
      plan = architecturePlanRepository.save(plan);

      // 5. Attach the DB ID to the response so callers can reference this plan later
      responseDto.setPlanId(plan.getId());

      log.info("Architecture plan saved with id={}, services={}, apis={}, events={}",
          plan.getId(),
          responseDto.getServices() != null ? responseDto.getServices().size() : 0,
          responseDto.getApis() != null ? responseDto.getApis().size() : 0,
          responseDto.getEvents() != null ? responseDto.getEvents().size() : 0);

      return responseDto;

    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini architecture response", e);
      throw new AiResponseParsingException(
          "JSON parse failure in generateArchitecture for idea='" + projectIdea + "'"
              + ". Raw response excerpt: "
              + (geminiResponse != null ? geminiResponse.substring(0, Math.min(geminiResponse.length(), 200)) : "null"),
          e);
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in generateArchitecture", e);
      throw new UnexpectedAiException("Unexpected error in generateArchitecture: " + e.getMessage(), e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Unified AI Code Analyzer
  // ─────────────────────────────────────────────────────────────────────────────

  public String analyzeCode(com.developerev.dto.AiAnalysisRequest request) {
    String type = request.getAnalysisType();
    if (type == null) {
      throw new IllegalArgumentException("analysisType cannot be null");
    }

    String prompt = "";

    switch (type.toLowerCase()) {
      case "explain":
        prompt = """
            Analyze the following code and provide:
            1. Code purpose
            2. Logic breakdown
            3. Time complexity
            4. Space complexity
            5. Improvement suggestions

            CODE:
            """ + request.getCode();
        break;
      case "debug":
        String errorContent = request.getErrorLog() != null ? request.getErrorLog() : request.getCode();
        prompt = """
            Analyze the following error and provide:
            1. Root cause
            2. Explanation
            3. Possible fix
            4. Code example fix

            ERROR:
            """ + errorContent;
        break;
      case "architecture":
        prompt = """
            Analyze this codebase and provide:
            1. Architecture style
            2. Design pattern suggestions
            3. Scalability issues
            4. Layer violations
            5. Refactoring suggestions

            CODEBASE:
            """ + request.getCode();
        break;
      case "performance":
        prompt = """
            Analyze this code and detect:
            1. Slow algorithms
            2. High complexity
            3. Memory issues
            4. Database inefficiencies

            CODE:
            """ + request.getCode();
        break;
      case "edge-case":
        prompt = """
            Analyze this code and detect missing edge cases.
            Provide:
            1. Missing validations
            2. Security edge cases
            3. Failure scenarios
            4. Recommended checks

            CODE:
            """ + request.getCode();
        break;
      case "complexity":
        prompt = """
            Analyze this code and calculate:
            1. Time complexity
            2. Space complexity
            3. Complexity explanation
            4. Optimization suggestions

            CODE:
            """ + request.getCode();
        break;
      case "refactor":
        prompt = """
            Analyze this code and detect refactoring opportunities.
            Provide:
            1. Large classes
            2. Large methods
            3. Duplicate logic
            4. Suggested class splits
            5. Design improvements

            CODE:
            """ + request.getCode();
        break;
      default:
        throw new IllegalArgumentException("Unknown analysisType: " + type);
    }

    log.info("Calling Gemini for {} analysis", type);
    String geminiResponse = geminiClient.callGemini(prompt);

    try {
      com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(geminiResponse);
      String textContent = root.path("candidates").get(0)
          .path("content")
          .path("parts").get(0)
          .path("text").asText();

      return textContent.replace("```markdown", "").replace("```", "").trim();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini explanation response", e);
      throw new com.developerev.ai.exception.AiResponseParsingException(
          "JSON parse failure in analyzeCode for type=" + type, e);
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in analyzeCode", e);
      throw new com.developerev.ai.exception.UnexpectedAiException("Unexpected error in analyzeCode: " + e.getMessage(),
          e);
    }
  }

}
