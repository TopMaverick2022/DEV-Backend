package com.developerev.service;

import com.developerev.ai.exception.AiResponseParsingException;
import com.developerev.ai.exception.UnexpectedAiException;
import com.developerev.dto.ArchitectureRequestDto;
import com.developerev.dto.ArchitectureResponseDto;
import com.developerev.dto.CriticalPathResponseDto;
import com.developerev.dto.DatabaseSchemaResponseDto;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AntiGravityService {

  private final AiClient aiClient;
  private final FeatureRepository featureRepository;
  private final TaskRepository taskRepository;
  private final SprintRepository sprintRepository;
  private final TaskDependencyRepository taskDependencyRepository;
  private final ArchitecturePlanRepository architecturePlanRepository;
  private final ObjectMapper objectMapper;
  private final ZipExtractorService zipExtractorService;
  private final DirectoryScannerService directoryScannerService;
  private final FileContentService fileContentService;
  private final KnowledgeService knowledgeService;
  private final ActivityLogService activityLogService;
  private final GitService gitService;
  private final com.developerev.repository.ProjectRepository projectRepository;

  public ProjectPlanResponseDto generateProjectPlan(Long projectId, String featureDescription) {

    com.developerev.model.Project project = projectRepository.findById(projectId).orElse(null);
    String stackContext = "";
    if (project != null && project.getLanguage() != null) {
      stackContext = String.format("""
          Project Stack Context:
          - Project Type: %s
          - Language: %s (version: %s)
          - Framework: %s (version: %s)
          - Database: %s (version: %s)
          - Selected Dependencies: %s
          """,
          project.getProjectType(),
          project.getLanguage(), project.getLanguageVersion(),
          project.getFramework(), project.getFrameworkVersion(),
          project.getDatabaseName(), project.getDatabaseVersion(),
          project.getDependencies());
    }

    // Inject linked project context when a companion project exists
    String linkedContext = "";
    if (project != null && project.getRelatedProjectId() != null) {
      com.developerev.model.Project linked = projectRepository.findById(project.getRelatedProjectId()).orElse(null);
      if (linked != null) {
        String companion = "FRONTEND".equals(project.getProjectType()) ? "Backend" : "Frontend";
        linkedContext = String.format("""

          Linked %s Project Context (this feature may span both projects — understand the full connectivity pipeline):
          - Project Name: %s
          - Project Type: %s
          - Language: %s (version: %s)
          - Framework: %s (version: %s)
          - Database: %s (version: %s)
          - Dependencies: %s
          %s
          """,
            companion,
            linked.getName(), linked.getProjectType(),
            linked.getLanguage(), linked.getLanguageVersion(),
            linked.getFramework(), linked.getFrameworkVersion(),
            linked.getDatabaseName(), linked.getDatabaseVersion(),
            linked.getDependencies(),
            (linked.getAiBusinessContext() != null
                ? "- AI Business Understanding: " + linked.getAiBusinessContext()
                : ""));
      }
    }

    String prompt = """
        You are a senior software architect.
        Return ONLY valid JSON.
        Do not wrap the response in markdown.
        Do not include ```json or ``` markers.
        No explanations. No markdown formatting.

        Based on the feature description and project stack context, auto-detect all the specific libraries, dependencies, framework modules, or tools needed for this feature (compatible with the project stack, e.g., if project is Java/Spring Boot and feature is JWT auth, auto-detect "Spring Security", "jjwt library", etc.) and return them in "detectedNeeds".

        Response Format:
        {
          "featureName": "",
          "complexity": "",
          "totalEstimatedHours": 0,
          "detectedNeeds": [
             "Dependency Name/Tool Name"
          ],
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

        """ + stackContext + linkedContext + "\nFeature:\n" + featureDescription;

    String aiResponse = aiClient.generateContent(prompt);

    try {
      String cleanedResponse = aiResponse
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
      if (responseDto.getDetectedNeeds() != null && !responseDto.getDetectedNeeds().isEmpty()) {
        feature.setDetectedNeeds(String.join(", ", responseDto.getDetectedNeeds()));
      }

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

          com.developerev.model.Task savedTask = taskRepository.save(task);
          taskDto.setId(savedTask.getId());
          taskDto.setStatus(savedTask.getStatus().name());
        }
      }

      responseDto.setFeatureId(feature.getId());

      activityLogService.logCurrentUserActivity(projectId, "Generated Project Plan", "Generated AI plan for feature: " + featureDescription);
      return responseDto;
    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini project-plan response", e);
      throw new AiResponseParsingException(
          "JSON parse failure in generateProjectPlan. Raw response excerpt: "
              + (aiResponse != null ? aiResponse.substring(0, Math.min(aiResponse.length(), 200)) : "null"),
          e);
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in generateProjectPlan", e);
      throw new UnexpectedAiException("Unexpected error in generateProjectPlan: " + e.getMessage(), e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // AI Plan Implementer
  // ─────────────────────────────────────────────────────────────────────────────

  public void implementPlan(Long featureId, String username) {
    // 1. Load feature and tasks
    Feature feature = featureRepository.findById(featureId)
        .orElseThrow(() -> new RuntimeException("Feature not found with id: " + featureId));

    List<Task> tasks = taskRepository.findByFeatureId(featureId);
    if (tasks.isEmpty()) {
      throw new RuntimeException("No tasks found for feature id: " + featureId + ". Generate a project plan first.");
    }

    StringBuilder taskList = new StringBuilder();
    for (Task task : tasks) {
      taskList.append("- ").append(task.getTitle()).append(": ").append(task.getDescription()).append("\n");
    }

    com.developerev.model.Project project = projectRepository.findById(feature.getProjectId()).orElse(null);
    String stackInfo = "";
    if (project != null && project.getLanguage() != null) {
      stackInfo = String.format("""
          Project Tech Stack Context:
          - Project Type: %s
          - Language: %s (version: %s)
          - Framework: %s (version: %s)
          - Database: %s (version: %s)
          - Base Stack Dependencies: %s
          """,
          project.getProjectType(),
          project.getLanguage(), project.getLanguageVersion(),
          project.getFramework(), project.getFrameworkVersion(),
          project.getDatabaseName(), project.getDatabaseVersion(),
          project.getDependencies());
    }

    // Inject linked project context for cross-project implementation awareness
    String linkedStackInfo = "";
    if (project != null && project.getRelatedProjectId() != null) {
      com.developerev.model.Project linked = projectRepository.findById(project.getRelatedProjectId()).orElse(null);
      if (linked != null) {
        String companion = "FRONTEND".equals(project.getProjectType()) ? "Backend" : "Frontend";
        linkedStackInfo = String.format("""

          Linked %s Project (understand API contracts, data models, and connectivity when implementing):
          - Name: %s (%s project)
          - Language: %s (version: %s)
          - Framework: %s (version: %s)
          - Database: %s (version: %s)
          - Dependencies: %s
          %s
          """,
            companion,
            linked.getName(), linked.getProjectType(),
            linked.getLanguage(), linked.getLanguageVersion(),
            linked.getFramework(), linked.getFrameworkVersion(),
            linked.getDatabaseName(), linked.getDatabaseVersion(),
            linked.getDependencies(),
            (linked.getAiBusinessContext() != null
                ? "- AI Business Context: " + linked.getAiBusinessContext()
                : ""));
      }
    }

    String featureNeedsInfo = "";
    if (feature.getDetectedNeeds() != null && !feature.getDetectedNeeds().isEmpty()) {
      featureNeedsInfo = "\nDetected stack needs/dependencies for this feature: " + feature.getDetectedNeeds() + "\n";
    }

    java.io.File repoDir = gitService.getRepoDir(feature.getProjectId());
    String projectStructure = directoryScannerService.getProjectStructure(repoDir.toPath());
    String projectStructureInfo = "";
    if (projectStructure != null && !projectStructure.isEmpty()) {
      projectStructureInfo = "\nExisting Project File/Folder Structure:\n" + projectStructure + "\n";
    }

    // 2. Build prompt
    String prompt = """
        You are a senior full-stack developer. Your task is to implement the code for a specific feature based on the plan, tech stack, existing project file/folder structure, and detected dependencies/needs provided.
        
        Analyze the existing project file/folder structure below. Align any new or modified files perfectly with this structure. For example, if there is a module/subdirectory (e.g., `DEV--backend`) containing the project codebase, make sure the paths in the generated JSON are nested correctly inside that subdirectory (e.g., starting with `DEV--backend/src/...`) rather than at the root level, so they integrate perfectly.
        
        Write the complete, production-ready code for the feature described below. Make sure the files generated are aligned with the specified language, framework, database, and dependencies. For example, if Maven/Gradle is used, update the pom.xml/build.gradle with any new dependencies if needed.
        
        Check the existing file paths carefully. If an existing file needs to be modified (like a `pom.xml`, config file, or existing controller/service/component), use its exact path from the project structure.
        
        Return ONLY valid JSON.
        Do not wrap the response in markdown.
        Do not include ```json or ``` markers.
        No explanations. No markdown formatting.
        
        Response format must strictly be:
        {
          "files": [
            {
              "path": "src/main/java/com/example/MyClass.java",
              "content": "package com.example;\\n\\npublic class MyClass {\\n}"
            }
          ]
        }
        
        """ + stackInfo + linkedStackInfo + featureNeedsInfo + projectStructureInfo + """
        
        Feature Description:
        """ + feature.getDescription() + "\n\nTasks:\n" + taskList.toString();

    log.info("Calling Gemini to implement plan for feature: {}", feature.getName());
    String aiResponse = aiClient.generateContent(prompt);

    try {
      String cleanedResponse = aiResponse
          .replace("```json", "")
          .replace("```", "")
          .trim();

      log.debug("Gemini implement plan response (cleaned): {}", cleanedResponse);

      JsonNode rootNode = objectMapper.readTree(cleanedResponse);
      JsonNode filesNode = rootNode.get("files");

      if (filesNode != null && filesNode.isArray()) {
        if (!repoDir.exists()) {
          repoDir.mkdirs();
        }

        for (JsonNode fileNode : filesNode) {
          String filePath = fileNode.get("path").asText();
          String fileContent = fileNode.get("content").asText();

          java.io.File targetFile = new java.io.File(repoDir, filePath);
          
          // Ensure parent directories exist
          if (!targetFile.getParentFile().exists()) {
            targetFile.getParentFile().mkdirs();
          }

          java.nio.file.Files.writeString(targetFile.toPath(), fileContent);
          log.info("Wrote generated file to {}", targetFile.getAbsolutePath());
        }
      }

      activityLogService.logCurrentUserActivity(feature.getProjectId(), "Implemented Plan", "AI implemented codebase for feature: " + feature.getName());

    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini implement plan response", e);
      throw new AiResponseParsingException("JSON parse failure in implementPlan.", e);
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in implementPlan", e);
      throw new UnexpectedAiException("Unexpected error in implementPlan: " + e.getMessage(), e);
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
    String aiResponse = aiClient.generateContent(prompt);

    try {
      String cleanedResponse = aiResponse
          .replace("```json", "")
          .replace("```", "")
          .trim();

      log.debug("Gemini sprint response (cleaned): {}", cleanedResponse);

      // 5. Parse Gemini response into DTO
      SprintAiResponseDto dtoResponse = objectMapper.readValue(cleanedResponse, SprintAiResponseDto.class);

      List<SprintDetailDto> result = new ArrayList<>();

      // 6. Persist each sprint and assign tasks by ID (exact, reliable lookup)
      for (SprintAiResponseDto.SprintItem sprintItem : dtoResponse.getSprints()) {

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
      activityLogService.logCurrentUserActivity(feature.getProjectId(), "Generated Sprints", "Generated AI sprints for feature: " + feature.getName());
      return result;

    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini sprint response", e);
      throw new AiResponseParsingException(
          "JSON parse failure in generateSprints for featureId=" + featureId
              + ". Raw response excerpt: "
              + (aiResponse != null ? aiResponse.substring(0, Math.min(aiResponse.length(), 200)) : "null"),
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
    String aiResponse = aiClient.generateContent(prompt);

    try {
      String cleanedResponse = aiResponse
          .replace("```json", "")
          .replace("```", "")
          .trim();

      log.debug("Gemini dependency response (cleaned): {}", cleanedResponse);

      // 4. Parse AI response
      DependencyAiResponseDto dtoResponse = objectMapper.readValue(cleanedResponse, DependencyAiResponseDto.class);

      List<TaskDependencyDto> result = new ArrayList<>();

      if (dtoResponse.getDependencies() != null) {
        for (DependencyAiResponseDto.DependencyItem item : dtoResponse.getDependencies()) {

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
      
      Long projectId = null;
      if (!tasks.isEmpty()) { projectId = tasks.get(0).getProjectId(); }
      activityLogService.logCurrentUserActivity(projectId, "Detected Dependencies", "Detected task dependencies for feature ID: " + featureId);
      
      return result;

    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini dependency response", e);
      throw new AiResponseParsingException(
          "JSON parse failure in detectDependencies for featureId=" + featureId
              + ". Raw response excerpt: "
              + (aiResponse != null ? aiResponse.substring(0, Math.min(aiResponse.length(), 200)) : "null"),
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

    Long projectId = null;
    if (!tasks.isEmpty()) { projectId = tasks.get(0).getProjectId(); }
    activityLogService.logCurrentUserActivity(projectId, "Computed Critical Path", "Computed critical path for feature ID: " + featureId);

    return new CriticalPathResponseDto(criticalPathHours, new ArrayList<>(path));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // AI Architecture Generator
  // ─────────────────────────────────────────────────────────────────────────────

  public String buildProjectContextPromptString(Long projectId) {
    if (projectId == null) {
      return "";
    }
    com.developerev.model.Project project = projectRepository.findById(projectId).orElse(null);
    if (project == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("\n=== GLOBAL SELECTED PROJECT CONTEXT ===\n");
    sb.append("Project Name: ").append(project.getName()).append("\n");
    if (project.getDescription() != null && !project.getDescription().isBlank()) {
      sb.append("Project Description/Use Case: ").append(project.getDescription()).append("\n");
    }
    if (project.getLanguage() != null && !project.getLanguage().isBlank()) {
      sb.append("Language: ").append(project.getLanguage());
      if (project.getLanguageVersion() != null && !project.getLanguageVersion().isBlank()) {
        sb.append(" (version: ").append(project.getLanguageVersion()).append(")");
      }
      sb.append("\n");
    }
    if (project.getFramework() != null && !project.getFramework().isBlank()) {
      sb.append("Framework: ").append(project.getFramework());
      if (project.getFrameworkVersion() != null && !project.getFrameworkVersion().isBlank()) {
        sb.append(" (version: ").append(project.getFrameworkVersion()).append(")");
      }
      sb.append("\n");
    }
    if (project.getDatabaseName() != null && !project.getDatabaseName().isBlank()) {
      sb.append("Database: ").append(project.getDatabaseName());
      if (project.getDatabaseVersion() != null && !project.getDatabaseVersion().isBlank()) {
        sb.append(" (version: ").append(project.getDatabaseVersion()).append(")");
      }
      sb.append("\n");
    }
    if (project.getDependencies() != null && !project.getDependencies().isBlank()) {
      sb.append("Dependencies: ").append(project.getDependencies()).append("\n");
    }
    // Inject deep AI-extracted business context if already analyzed
    if (project.getAiBusinessContext() != null && !project.getAiBusinessContext().isBlank()) {
      sb.append("\n--- Deep Business Context (AI-Extracted from Codebase) ---\n");
      sb.append(project.getAiBusinessContext()).append("\n");
      sb.append("----------------------------------------------------------\n");
    }
    sb.append("=========================================\n");
    return sb.toString();
  }

  /**
   * Scans the actual project codebase file/folder structure and uses the LLM to
   * deduce the real business domain, entities, and workflows. The result is stored
   * in the project's aiBusinessContext column so that ALL subsequent AI requests
   * (architecture, planner, explainer, etc.) automatically benefit from deep
   * domain knowledge — without re-scanning on every request.
   */
  public String analyzeAndStoreProjectContext(Long projectId) {
    com.developerev.model.Project project = projectRepository.findById(projectId)
        .orElseThrow(() -> new RuntimeException("Project not found: " + projectId));

    java.io.File repoDir = gitService.getRepoDir(projectId);
    String fileStructure = directoryScannerService.getProjectStructure(repoDir.toPath());

    if (fileStructure == null || fileStructure.isBlank()) {
      throw new RuntimeException(
          "No codebase found for this project. Please upload or clone the repository first.");
    }

    // Read a sample of key file contents to improve domain understanding
    StringBuilder fileSamples = new StringBuilder();
    try {
      java.nio.file.Files.walk(repoDir.toPath())
          .filter(p -> !java.nio.file.Files.isDirectory(p))
          .filter(p -> {
            String name = p.getFileName().toString().toLowerCase();
            return name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".tsx")
                || name.endsWith(".py") || name.endsWith(".js") || name.endsWith(".go")
                || name.endsWith(".cs") || name.endsWith(".kt");
          })
          .limit(30) // Cap at 30 files to stay within token budget
          .forEach(p -> {
            try {
              String content = java.nio.file.Files.readString(p);
              // Take only the first 60 lines of each file (top-level declarations & imports)
              String[] lines = content.split("\n");
              int limit = Math.min(60, lines.length);
              StringBuilder sample = new StringBuilder();
              for (int i = 0; i < limit; i++) {
                sample.append(lines[i]).append("\n");
              }
              fileSamples.append("\n--- ").append(repoDir.toPath().relativize(p)).append(" ---\n");
              fileSamples.append(sample);
            } catch (Exception ignored) {}
          });
    } catch (Exception e) {
      log.warn("Could not read file samples for project {}: {}", projectId, e.getMessage());
    }

    String prompt = """
        You are a senior software architect performing a deep codebase analysis.

        Analyze the project file/folder structure and the code samples below.
        Your goal is to deduce:
        1. The core business domain (e.g. Travel Booking, E-Commerce, Healthcare)
        2. The main entities/models and what they represent (e.g. Traveler, Flight, Booking, Hotel)
        3. The key workflows and processes (e.g. user books a flight, payment is processed, confirmation is sent)
        4. The major services/modules and their responsibilities
        5. Any external integrations or APIs present

        Write a concise, dense business context summary in plain English (max 400 words).
        This summary will be injected into every AI prompt for this project.
        Do NOT include technical jargon that isn't domain-specific.
        Focus on WHAT the system does for its users, not HOW it is implemented.
        Do not include any markdown headers or formatting.

        Project File Structure:
        """ + fileStructure + """

        Code Samples (first 60 lines of up to 30 files):
        """ + fileSamples;

    log.info("Calling AI to extract deep business context for project {}", projectId);
    String aiResponse = aiClient.generateContent(prompt);

    String cleanedContext = aiResponse
        .replace("```", "")
        .trim();

    project.setAiBusinessContext(cleanedContext);
    projectRepository.save(project);

    log.info("Stored AI business context for project {}: {} chars", projectId, cleanedContext.length());
    activityLogService.logCurrentUserActivity(projectId, "Analyzed Codebase", "AI extracted deep business context from codebase.");

    return cleanedContext;
  }

  /**
   * Classifies a project as FRONTEND, BACKEND, FULLSTACK, or UNKNOWN
   * by inspecting the stored language and framework fields.
   *
   * Frontend signals:  React, Angular, Vue, Next.js, Nuxt, Svelte, Gatsby, Vite (+TS/JS)
   * Backend signals:   Spring Boot, Django, FastAPI, Express, Laravel, Rails, ASP.NET, Gin, NestJS (server-side)
   * Fullstack:         Next.js with API routes, Nuxt, SvelteKit, or if both frontend+backend deps appear
   */
  private String detectProjectType(Long projectId) {
    if (projectId == null) return "BACKEND";
    com.developerev.model.Project project = projectRepository.findById(projectId).orElse(null);
    if (project == null) return "BACKEND";

    String framework = project.getFramework() != null ? project.getFramework().toLowerCase() : "";
    String language  = project.getLanguage()  != null ? project.getLanguage().toLowerCase()  : "";
    String deps      = project.getDependencies() != null ? project.getDependencies().toLowerCase() : "";
    String combined  = framework + " " + language + " " + deps;

    // Pure frontend frameworks
    boolean isFrontend = combined.matches(".*\\b(react|angular|vue|svelte|gatsby|vite|ionic|flutter|react native|expo)\\b.*")
        && !combined.matches(".*\\b(spring|django|fastapi|express|laravel|rails|asp\\.net|gin|nestjs|hapi|koa|flask|quarkus|micronaut|struts|play framework)\\b.*");

    // Fullstack frameworks (have both UI and server-side rendering/API)
    boolean isFullstack = combined.matches(".*\\b(next\\.?js|nextjs|nuxt|sveltekit|remix|blitz|redwood)\\b.*");

    // Explicit backend signals
    boolean isBackend = combined.matches(".*\\b(spring|spring boot|django|fastapi|express|laravel|rails|asp\\.net|gin|nestjs|hapi|koa|flask|quarkus|micronaut|struts|play framework|actix|axum|fiber)\\b.*");

    if (isFullstack) return "FULLSTACK";
    if (isFrontend)  return "FRONTEND";
    if (isBackend)   return "BACKEND";

    // Fallback: TypeScript/JavaScript projects without explicit framework → likely frontend
    if (language.contains("typescript") || language.contains("javascript")) {
      return "FRONTEND";
    }

    return "BACKEND"; // Java, Python, Go, C#, etc. → backend by default
  }

  public ArchitectureResponseDto generateArchitecture(ArchitectureRequestDto request) {
    String projectContext = buildProjectContextPromptString(request.getProjectId());

    // ── Detect project type from tech stack ───────────────────────────────────
    String projectType = detectProjectType(request.getProjectId());

    // ── Build a type-specific prompt ──────────────────────────────────────────
    String typeSpecificInstructions;
    if ("FRONTEND".equals(projectType)) {
      typeSpecificInstructions = """
          Design a FRONTEND application architecture diagram.

          This is a frontend/UI project. Do NOT generate backend microservices, database nodes, or server-side services.
          Instead, model the architecture of the frontend application itself:

          "services" = UI Modules / Pages / Feature Areas (e.g. "LoginPage", "DashboardModule", "CartPage", "AuthStore", "ApiService")
            - "database" field should be "none" for all UI modules.
            - For state management stores (Redux, Zustand, Context), use the store name (e.g. "AuthContext", "CartStore").

          "apis" = Backend API calls consumed by this frontend (list the actual HTTP endpoints this UI calls)
            - Use the HTTP method and realistic endpoint paths.

          "events" = Data flows and user interaction flows between frontend modules
            (e.g. user logs in → AuthStore updates → DashboardModule re-renders)
            - "producer" and "consumer" must be exact names from "services".
            - Name events as user/data flows, e.g. "UserLoginFlow", "CartUpdatedEvent", "AuthTokenStored".

          STRICT RULES:
          1. Every "producer" and "consumer" in "events" MUST be copied character-for-character from a "name" value in "services".
          2. Do NOT generate any backend services, databases, or server-side components.
          3. Focus on pages, components, state managers, API service layers, and routing.
          4. Use short, clear module names (e.g. "LoginPage", "ProductList", "AuthStore", "ApiService").
          """;
    } else if ("FULLSTACK".equals(projectType)) {
      typeSpecificInstructions = """
          Design a FULLSTACK application architecture diagram showing BOTH the frontend and backend tiers.

          Include both:
          - Frontend modules (Pages, Components, State stores, API service layers) — set "database" to "none"
          - Backend services with their databases
          - Clear data flow events between frontend and backend layers

          STRICT RULES:
          1. Every "producer" and "consumer" in "events" MUST be copied character-for-character from a "name" value in "services".
          2. Clearly separate frontend modules (suffix: "Page", "Component", "Store") from backend services (suffix: "Service", "Controller").
          3. Every service must connect to at least one event.
          """;
    } else {
      // Default: BACKEND / UNKNOWN
      typeSpecificInstructions = """
          Design a BACKEND microservice architecture for the given idea.

          STRICT RULES:
          1. Every "producer" and "consumer" in "events" MUST be copied character-for-character from a "name" value in "services".
          2. Do NOT invent new names for producers or consumers. Only reuse existing service names.
          3. Every service must connect to at least one event (as producer OR consumer).
          4. Use short, clear service names (e.g. "AuthService", "OrderService").
          """;
    }

    String prompt = """
        You are a senior software architect.
        Return ONLY valid JSON. No markdown. No ``` markers. No explanations.

        """ + typeSpecificInstructions + """

        Response Format:
        {
          "services": [
            {
              "name": "AuthService",
              "description": "Handles user registration, login, JWT token issuance and validation.",
              "database": "PostgreSQL"
            }
          ],
          "apis": [
            {
              "method": "POST",
              "endpoint": "/api/auth/login",
              "description": "Authenticates user and returns JWT."
            }
          ],
          "events": [
            {
              "name": "UserRegisteredEvent",
              "producer": "AuthService",
              "consumer": "NotificationService"
            }
          ]
        }
        """ + projectContext + """

        Idea / Requirements:
        """ + request.getIdea();


    String aiResponse = aiClient.generateContent(prompt);

    try {
      String cleanedResponse = aiResponse
          .replace("```json", "")
          .replace("```", "")
          .trim();

      return objectMapper.readValue(cleanedResponse, ArchitectureResponseDto.class);
    } catch (Exception e) {
      log.error("Failed to parse AI response for architecture: {}", aiResponse, e);
      throw new RuntimeException("Failed to generate architecture: " + e.getMessage(), e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // AI Database Intelligence Engine
  // ─────────────────────────────────────────────────────────────────────────────

  public DatabaseSchemaResponseDto generateDatabaseSchema(String featureDescription, Long projectId) {
    String projectContext = buildProjectContextPromptString(projectId);
    String prompt = """
        You are a senior database architect.
        
        Convert the following user feature description into an optimized database schema.
        
        Include:
        1. Entities and Tables
        2. Columns with precise SQL data types (like BIGINT, VARCHAR, DECIMAL, TIMESTAMP)
        3. Primary keys
        4. Relationships
        5. Indexes
        
        Return ONLY valid JSON.
        Do not wrap the response in markdown.
        Do not include ```json or ``` markers.
        No explanations outside of the JSON.
        
        Response format:
        {
          "tables": [
            {
              "name": "Orders",
              "columns": [
                {"name": "order_id", "type": "BIGINT", "primaryKey": true},
                {"name": "user_id", "type": "BIGINT", "primaryKey": false}
              ]
            }
          ],
          "relationships": [
            "Users 1:N Orders"
          ],
          "indexes": [
            "INDEX idx_user_id ON Orders(user_id)"
          ]
        }
        """ + projectContext + """
        
        Feature Description:
        """ + featureDescription;

    log.info("Calling Gemini for Database Schema Generation: {}", featureDescription);
    String aiResponse = aiClient.generateContent(prompt);

    try {
      String cleanedResponse = aiResponse
          .replace("```json", "")
          .replace("```", "")
          .trim();

      log.debug("Gemini database schema response (cleaned): {}", cleanedResponse);

      activityLogService.logCurrentUserActivity(null, "Generated Database Schema", "Generated DB schema design");

      return objectMapper.readValue(cleanedResponse, DatabaseSchemaResponseDto.class);

    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini Database Schema response", e);
      throw new AiResponseParsingException(
          "JSON parse failure in generateDatabaseSchema. Raw response excerpt: "
              + (aiResponse != null ? aiResponse.substring(0, Math.min(aiResponse.length(), 200)) : "null"),
          e);
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in generateDatabaseSchema", e);
      throw new UnexpectedAiException("Unexpected error in generateDatabaseSchema: " + e.getMessage(), e);
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

    String knowledgeContext = buildProjectContextPromptString(request.getProjectId()) + knowledgeService.buildKnowledgePromptString();

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
            """ + request.getCode() + knowledgeContext;
        break;
      case "debug":
        String errorContent = request.getErrorLog() != null ? request.getErrorLog() : request.getCode();
        prompt = """
            You are a senior debugging expert.

            Analyze the following error log and stack trace.

            Provide:

            1. Root cause
            2. Detailed explanation
            3. Possible fix
            4. Example code fix
            5. Prevention tips

            ERROR:
            """ + errorContent + knowledgeContext;
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
            """ + request.getCode() + knowledgeContext;
        break;
      case "performance":
        prompt = """
            You are a performance optimization expert.

            Analyze the following code.

            Detect:

            1. Slow algorithms
            2. Inefficient loops
            3. Memory usage problems
            4. Expensive operations

            Provide optimized suggestions.

            CODE:
            """ + request.getCode() + knowledgeContext;
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
            """ + request.getCode() + knowledgeContext;
        break;
      case "complexity":
        prompt = """
            Analyze this code and calculate:
            1. Time complexity
            2. Space complexity
            3. Complexity explanation
            4. Optimization suggestions

            CODE:
            """ + request.getCode() + knowledgeContext;
        break;
      case "refactor":
        prompt = """
            You are a senior software architect.

            Analyze the following code and suggest refactoring.

            Provide:

            1. Code smells
            2. Large methods
            3. Duplicate logic
            4. Suggested refactoring
            5. Improved structure

            CODE:
            """ + request.getCode() + knowledgeContext;
        break;
      case "security-scan":
        prompt = """
            You are a cybersecurity expert.

            Analyze the following code for security vulnerabilities.

            Detect:

            1. SQL Injection
            2. XSS
            3. Authentication flaws
            4. Unsafe deserialization
            5. Sensitive data exposure

            CODE:
            """ + request.getCode() + knowledgeContext;
        break;
      case "test-generator":
        prompt = """
            Generate unit tests for this code.

            Include:

            1. Normal test cases
            2. Edge cases
            3. Failure cases

            Use JUnit 5.

            CODE:
            """ + request.getCode() + knowledgeContext;
        break;
      case "generate-docs":
        prompt = """
            Generate developer documentation.

            Include:

            1. API description
            2. Method explanations
            3. Usage examples

            CODE:
            """ + request.getCode() + knowledgeContext;
        break;
      default:
        throw new IllegalArgumentException("Unknown analysisType: " + type);
    }

    log.info("Calling Gemini for {} analysis", type);
    String aiResponse = aiClient.generateContent(prompt);

    try {
      String answer = aiResponse.replace("```markdown", "").replace("```", "").trim();
      activityLogService.logCurrentUserActivity(null, "Analyzed Code", "Ran " + type + " analysis on codebase");
      return answer;
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in analyzeCode", e);
      throw new com.developerev.ai.exception.UnexpectedAiException("Unexpected error in analyzeCode: " + e.getMessage(),
          e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Generic AI Prompt Engine
  // ─────────────────────────────────────────────────────────────────────────────

  public String askAI(String prompt) {
    log.info("Calling AI with custom prompt length: {}", prompt.length());
    String aiResponse = aiClient.generateContent(prompt);

    try {
      return aiResponse.replace("```json", "").replace("```markdown", "").replace("```", "").trim();
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in askAI", e);
      throw new com.developerev.ai.exception.UnexpectedAiException("Unexpected error in askAI: " + e.getMessage(), e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Dependency Graph Generator
  // ─────────────────────────────────────────────────────────────────────────────

  public String generateDependencyGraph(MultipartFile zipFile) {
      Path tempDir = null;
      try {
          tempDir = zipExtractorService.extractZip(zipFile);
          List<Path> sourceFiles = directoryScannerService.scan(tempDir);

          StringBuilder codeBase = new StringBuilder();
          for (Path file : sourceFiles) {
              String filename = file.getFileName().toString();
              String content = fileContentService.readFile(file);
              codeBase.append("\n--- ").append(filename).append(" ---\n");
              codeBase.append(content).append("\n");
          }

          String prompt = """
              Analyze this project and generate dependency graph.

              Provide:

              1. Modules
              2. Dependencies
              3. Circular dependencies
              4. Architecture diagram text

              CODEBASE:
              """ + codeBase.toString();

          return askAI(prompt);
      } catch (IOException e) {
          log.error("Error processing zip file for dependency graph", e);
          throw new RuntimeException("Error processing zip file: " + e.getMessage(), e);
      } finally {
          if (tempDir != null) {
              zipExtractorService.cleanup(tempDir);
          }
      }
  }

  public List<com.developerev.dto.RecommendedDependencyDto> recommendDependencies(com.developerev.dto.RecommendDependenciesRequestDto request) {
    String prompt = String.format("""
        You are a senior software architect.
        Recommend a list of optional development dependencies or packages for a new project with the following stack:
        - Language: %s (version %s)
        - Framework: %s (version %s)
        - Database: %s (version %s)

        Return ONLY valid JSON.
        Do not wrap the response in markdown.
        Do not include ```json or ``` markers.
        No explanations. No markdown formatting.

        Response format must strictly be a JSON array of objects:
        [
          {
            "name": "Dependency/Library Name",
            "description": "Brief description of why it is useful",
            "checked": true
          }
        ]

        Provide about 4 to 8 relevant dependencies/libraries for this specific stack. Set "checked" to true for highly recommended ones, and false for other optional ones.
        """,
        request.getLanguage() != null ? request.getLanguage() : "Any",
        request.getLanguageVersion() != null ? request.getLanguageVersion() : "Any",
        request.getFramework() != null ? request.getFramework() : "Any",
        request.getFrameworkVersion() != null ? request.getFrameworkVersion() : "Any",
        request.getDatabase() != null ? request.getDatabase() : "Any",
        request.getDatabaseVersion() != null ? request.getDatabaseVersion() : "Any"
    );

    String aiResponse = aiClient.generateContent(prompt);
    try {
      String cleanedResponse = aiResponse
          .replace("```json", "")
          .replace("```", "")
          .trim();
      
      return objectMapper.readValue(cleanedResponse, new com.fasterxml.jackson.core.type.TypeReference<List<com.developerev.dto.RecommendedDependencyDto>>() {});
    } catch (Exception e) {
      log.error("Failed to parse recommended dependencies response: {}", aiResponse, e);
      List<com.developerev.dto.RecommendedDependencyDto> fallbacks = new ArrayList<>();
      if ("Java".equalsIgnoreCase(request.getLanguage())) {
        fallbacks.add(new com.developerev.dto.RecommendedDependencyDto("Lombok", "Java library that reduces boilerplate code", true));
        fallbacks.add(new com.developerev.dto.RecommendedDependencyDto("Spring Security", "Authentication and access-control framework", false));
      } else if ("JavaScript".equalsIgnoreCase(request.getLanguage()) || "TypeScript".equalsIgnoreCase(request.getLanguage())) {
        fallbacks.add(new com.developerev.dto.RecommendedDependencyDto("Axios", "Promise based HTTP client", true));
        fallbacks.add(new com.developerev.dto.RecommendedDependencyDto("Zod", "TypeScript-first schema validation", false));
      }
      return fallbacks;
    }
  }

}
