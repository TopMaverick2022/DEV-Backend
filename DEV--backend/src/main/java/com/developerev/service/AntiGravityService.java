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

  // ─────────────────────────────────────────────────────────────────────────────
  // Auto Stack Detection from Cloned Repo
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Detects language, framework, and version by inspecting build/config files
   * inside the cloned repo directory. Used as a fallback when the project's
   * language/framework fields are blank (e.g. project imported by Git clone).
   *
   * Detects: Java/Maven(Spring Boot), Java/Gradle, TypeScript/React, TypeScript/Next.js,
   * TypeScript/Angular, TypeScript/Vue, TypeScript/NestJS, JavaScript/Node,
   * Python/Django, Python/FastAPI, Python/Flask, Go, Rust/Actix, C#/.NET,
   * Ruby/Rails, PHP/Laravel, Dart/Flutter.
   *
   * Also persists detected values back to the project row so future calls
   * skip this scan entirely.
   */
  private DetectedStack detectStackFromRepo(java.io.File repoDir, com.developerev.model.Project project) {
    if (repoDir == null || !repoDir.exists()) return new DetectedStack("", "", "");

    try {
      // ── pom.xml → Java / Maven ──────────────────────────────────────────────
      java.io.File pom = new java.io.File(repoDir, "pom.xml");
      if (!pom.exists()) {
        // Check one level deeper (e.g. monorepo with a backend subfolder)
        pom = java.nio.file.Files.walk(repoDir.toPath(), 2)
            .filter(p -> p.getFileName().toString().equals("pom.xml"))
            .map(java.nio.file.Path::toFile)
            .findFirst().orElse(null);
      }
      if (pom != null && pom.exists()) {
        String content = java.nio.file.Files.readString(pom.toPath());
        String version = extractMavenJavaVersion(content);
        String framework = "";
        if (content.contains("spring-boot")) framework = "Spring Boot";
        else if (content.contains("quarkus"))   framework = "Quarkus";
        else if (content.contains("micronaut")) framework = "Micronaut";
        persistDetectedStack(project, "Java", framework, version);
        return new DetectedStack("Java", framework, version);
      }

      // ── build.gradle → Java or Kotlin / Gradle ──────────────────────────────
      java.io.File gradle = findFile(repoDir, "build.gradle", 2);
      java.io.File gradleKts = findFile(repoDir, "build.gradle.kts", 2);
      java.io.File gradleFile = gradleKts != null ? gradleKts : gradle;
      if (gradleFile != null) {
        String content = java.nio.file.Files.readString(gradleFile.toPath());
        String lang = gradleFile.getName().endsWith(".kts") ? "Kotlin" : "Java";
        String framework = "";
        if (content.contains("spring-boot"))   framework = "Spring Boot";
        else if (content.contains("quarkus"))  framework = "Quarkus";
        persistDetectedStack(project, lang, framework, "");
        return new DetectedStack(lang, framework, "");
      }

      // ── composer.json → PHP / Laravel ────────────────────────────────────────
      java.io.File composer = findFile(repoDir, "composer.json", 2);
      if (composer != null) {
        String content = java.nio.file.Files.readString(composer.toPath()).toLowerCase();
        String framework = "";
        if (content.contains("laravel/framework"))      framework = "Laravel";
        else if (content.contains("symfony/symfony"))  framework = "Symfony";
        persistDetectedStack(project, "PHP", framework, "");
        return new DetectedStack("PHP", framework, "");
      }

      // ── package.json → TypeScript or JavaScript ──────────────────────────────
      java.io.File pkg = findFile(repoDir, "package.json", 2);
      if (pkg != null) {
        String content = java.nio.file.Files.readString(pkg.toPath());
        // TypeScript if tsconfig exists alongside, or ts in deps
        boolean hasTs = content.contains("\"typescript\"") || findFile(repoDir, "tsconfig.json", 2) != null;
        String lang = hasTs ? "TypeScript" : "JavaScript";
        String framework = "";
        String version = "";
        if (content.contains("\"next\""))          { framework = "Next.js";  version = extractNpmVersion(content, "next"); }
        else if (content.contains("\"react\""))    { framework = "React";    version = extractNpmVersion(content, "react"); }
        else if (content.contains("\"@angular")   ) { framework = "Angular"; }
        else if (content.contains("\"vue\""))      { framework = "Vue.js";   version = extractNpmVersion(content, "vue"); }
        else if (content.contains("\"@nestjs")     ) { framework = "NestJS"; }
        else if (content.contains("\"svelte\""))   { framework = "Svelte"; }
        else if (content.contains("\"express\""))  { framework = "Express"; }
        else if (content.contains("\"fastify\""))  { framework = "Fastify"; }
        persistDetectedStack(project, lang, framework, version);
        return new DetectedStack(lang, framework, version);
      }

      // ── requirements.txt / pyproject.toml → Python ──────────────────────────
      java.io.File req = findFile(repoDir, "requirements.txt", 2);
      java.io.File pyproject = findFile(repoDir, "pyproject.toml", 2);
      String pyContent = "";
      if (req != null) pyContent += java.nio.file.Files.readString(req.toPath()).toLowerCase();
      if (pyproject != null) pyContent += java.nio.file.Files.readString(pyproject.toPath()).toLowerCase();
      if (!pyContent.isBlank()) {
        String framework = "";
        if (pyContent.contains("django"))       framework = "Django";
        else if (pyContent.contains("fastapi")) framework = "FastAPI";
        else if (pyContent.contains("flask"))   framework = "Flask";
        else if (pyContent.contains("tornado")) framework = "Tornado";
        persistDetectedStack(project, "Python", framework, "");
        return new DetectedStack("Python", framework, "");
      }

      // ── go.mod → Go ──────────────────────────────────────────────────────────
      java.io.File goMod = findFile(repoDir, "go.mod", 2);
      if (goMod != null) {
        String content = java.nio.file.Files.readString(goMod.toPath()).toLowerCase();
        String framework = "";
        if (content.contains("github.com/gin-gonic"))  framework = "Gin";
        else if (content.contains("github.com/gofiber")) framework = "Fiber";
        else if (content.contains("github.com/labstack")) framework = "Echo";
        String goVersion = "";
        for (String line : content.split("\n")) {
          if (line.startsWith("go ")) { goVersion = line.replace("go ", "").trim(); break; }
        }
        persistDetectedStack(project, "Go", framework, goVersion);
        return new DetectedStack("Go", framework, goVersion);
      }

      // ── Cargo.toml → Rust ────────────────────────────────────────────────────
      java.io.File cargo = findFile(repoDir, "Cargo.toml", 2);
      if (cargo != null) {
        String content = java.nio.file.Files.readString(cargo.toPath()).toLowerCase();
        String framework = "";
        if (content.contains("actix"))  framework = "Actix-web";
        else if (content.contains("axum")) framework = "Axum";
        else if (content.contains("rocket")) framework = "Rocket";
        persistDetectedStack(project, "Rust", framework, "");
        return new DetectedStack("Rust", framework, "");
      }

      // ── *.csproj → C# / .NET ─────────────────────────────────────────────────
      java.util.Optional<java.nio.file.Path> csproj = java.nio.file.Files.walk(repoDir.toPath(), 3)
          .filter(p -> p.toString().endsWith(".csproj")).findFirst();
      if (csproj.isPresent()) {
        String content = java.nio.file.Files.readString(csproj.get()).toLowerCase();
        String framework = content.contains("aspnet") || content.contains("microsoft.aspnetcore") ? "ASP.NET Core" : "";
        persistDetectedStack(project, "C#", framework, "");
        return new DetectedStack("C#", framework, "");
      }

      // ── Gemfile → Ruby ───────────────────────────────────────────────────────
      java.io.File gemfile = findFile(repoDir, "Gemfile", 2);
      if (gemfile != null) {
        String content = java.nio.file.Files.readString(gemfile.toPath()).toLowerCase();
        String framework = content.contains("rails") ? "Ruby on Rails" : content.contains("sinatra") ? "Sinatra" : "";
        persistDetectedStack(project, "Ruby", framework, "");
        return new DetectedStack("Ruby", framework, "");
      }

      // ── pubspec.yaml → Dart / Flutter ────────────────────────────────────────
      java.io.File pubspec = findFile(repoDir, "pubspec.yaml", 2);
      if (pubspec != null) {
        persistDetectedStack(project, "Dart", "Flutter", "");
        return new DetectedStack("Dart", "Flutter", "");
      }

      // ── Fallback 1: Dynamic AI detection using Gemini ─────────────────────────
      try {
        log.info("[STACK_DETECT] Running dynamic AI stack detection for project {}", project.getId());
        String projectStructure = directoryScannerService.getProjectStructure(repoDir.toPath());
        if (projectStructure != null && !projectStructure.isEmpty()) {
          String detectionPrompt = """
              You are an expert system utility. Analyze the project file/folder structure below:
              
              %s
              
              Identify:
              1. The primary programming language (e.g. Java, PHP, Python, Go, Rust, TypeScript, C#, Ruby, Dart, etc.)
              2. The primary framework (e.g. Spring Boot, Laravel, Django, Gin, Actix, React, Angular, Vue, Next.js, Rails, ASP.NET Core, etc.)
              3. The approximate language or framework version if it can be inferred from config files.
              
              Return ONLY a valid JSON object.
              Do not wrap the response in markdown.
              Do not include ```json or ``` markers.
              
              Format:
              {
                "language": "LanguageName",
                "framework": "FrameworkName",
                "version": "VersionOrEmpty"
              }
              """.formatted(projectStructure);

          String aiResponse = aiClient.generateContent(detectionPrompt);
          String cleanedResponse = aiResponse
              .replace("```json", "")
              .replace("```", "")
              .trim();

          JsonNode rootNode = objectMapper.readTree(cleanedResponse);
          String lang = rootNode.path("language").asText("").trim();
          String framework = rootNode.path("framework").asText("").trim();
          String version = rootNode.path("version").asText("").trim();

          if (!lang.isEmpty() && !"Unknown".equalsIgnoreCase(lang)) {
            persistDetectedStack(project, lang, framework, version);
            return new DetectedStack(lang, framework, version);
          }
        }
      } catch (Exception e) {
        log.warn("[STACK_DETECT] AI stack detection failed, falling back to file extension counting: {}", e.getMessage());
      }

      // ── Fallback 2: count file extensions in repo ───────────────────────────────
      java.util.Map<String, Long> extCounts = java.nio.file.Files.walk(repoDir.toPath(), 4)
          .filter(p -> !java.nio.file.Files.isDirectory(p))
          .map(p -> { String n = p.getFileName().toString(); int d = n.lastIndexOf('.'); return d >= 0 ? n.substring(d + 1).toLowerCase() : ""; })
          .filter(e -> !e.isBlank())
          .collect(java.util.stream.Collectors.groupingBy(e -> e, java.util.stream.Collectors.counting()));

      String dominant = extCounts.entrySet().stream()
          .filter(e -> java.util.List.of("java","kt","py","ts","tsx","js","jsx","go","rs","cs","rb","php","dart").contains(e.getKey()))
          .max(java.util.Map.Entry.comparingByValue())
          .map(java.util.Map.Entry::getKey).orElse("");

      String detected = switch (dominant) {
        case "java" -> "Java";
        case "kt"   -> "Kotlin";
        case "py"   -> "Python";
        case "ts", "tsx" -> "TypeScript";
        case "js", "jsx" -> "JavaScript";
        case "go"   -> "Go";
        case "rs"   -> "Rust";
        case "cs"   -> "C#";
        case "rb"   -> "Ruby";
        case "php"  -> "PHP";
        case "dart" -> "Dart";
        default     -> "";
      };
      if (!detected.isBlank()) {
        persistDetectedStack(project, detected, "", "");
        return new DetectedStack(detected, "", "");
      }

    } catch (Exception e) {
      log.warn("[STACK_DETECT] Error scanning repo for stack detection: {}", e.getMessage());
    }
    return new DetectedStack("", "", "");
  }

  /** Simple value record to carry detected language/framework/version. */
  private record DetectedStack(String language, String framework, String version) {
    boolean hasLanguage() { return language != null && !language.isBlank(); }
  }

  /** Persists detected values only if the project fields are currently blank. */
  private void persistDetectedStack(com.developerev.model.Project project, String lang, String fw, String ver) {
    boolean changed = false;
    if ((project.getLanguage() == null || project.getLanguage().isBlank()) && !lang.isBlank()) {
      project.setLanguage(lang); changed = true;
    }
    if ((project.getFramework() == null || project.getFramework().isBlank()) && !fw.isBlank()) {
      project.setFramework(fw); changed = true;
    }
    if ((project.getLanguageVersion() == null || project.getLanguageVersion().isBlank()) && !ver.isBlank()) {
      project.setLanguageVersion(ver); changed = true;
    }
    if (changed) {
      projectRepository.save(project);
      log.info("[STACK_DETECT] Auto-detected and persisted stack for project {}: lang={} fw={} ver={}",
          project.getId(), lang, fw, ver);
    }
  }

  /** Finds a file by name within maxDepth levels of root. Returns null if not found. */
  private java.io.File findFile(java.io.File root, String filename, int maxDepth) {
    try {
      return java.nio.file.Files.walk(root.toPath(), maxDepth)
          .filter(p -> p.getFileName().toString().equals(filename))
          .map(java.nio.file.Path::toFile)
          .findFirst().orElse(null);
    } catch (Exception e) { return null; }
  }

  /** Extracts Java source version from pom.xml content. */
  private String extractMavenJavaVersion(String pomContent) {
    // Looks for <java.version>17</java.version> or <maven.compiler.source>17</maven.compiler.source>
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("<(?:java\\.version|maven\\.compiler\\.source|maven\\.compiler\\.release)>(\\d+)</", java.util.regex.Pattern.DOTALL)
        .matcher(pomContent);
    return m.find() ? m.group(1) : "";
  }

  /** Extracts a package version from package.json content (handles ^ and ~ prefixes). */
  private String extractNpmVersion(String pkgContent, String pkg) {
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("\"" + java.util.regex.Pattern.quote(pkg) + "\"\\s*:\\s*\"[~^]?([\\d.]+)")
        .matcher(pkgContent);
    return m.find() ? m.group(1) : "";
  }



  public com.developerev.dto.PrecheckFeatureResponseDto precheckProjectFeature(Long projectId, String featureDescription) {
    java.io.File repoDir = gitService.getRepoDir(projectId);
    String projectKnowledgeContext = "";
    if (repoDir != null && repoDir.exists()) {
      try {
        String projectStructure = directoryScannerService.getProjectStructure(repoDir.toPath());
        if (projectStructure != null && !projectStructure.isEmpty()) {
          projectKnowledgeContext += "\nExisting Project File/Folder Structure:\n" + projectStructure + "\n";
          
          StringBuilder codebaseBuilder = new StringBuilder();
          java.nio.file.Files.walk(repoDir.toPath())
              .filter(java.nio.file.Files::isRegularFile)
              .filter(p -> {
                  String name = p.getFileName().toString().toLowerCase();
                  return name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".py") 
                         || name.endsWith(".php") || name.endsWith(".go") || name.endsWith(".js") || name.endsWith(".css") 
                         || name.endsWith(".html") || name.endsWith(".json") || name.endsWith(".xml");
              })
              .forEach(p -> {
                  try {
                      String content = java.nio.file.Files.readString(p);
                      codebaseBuilder.append("\n--- ").append(repoDir.toPath().relativize(p).toString().replace("\\", "/")).append(" ---\n");
                      codebaseBuilder.append(content).append("\n");
                  } catch (Exception ignored) {}
              });
          
          String fullCode = codebaseBuilder.toString();
          if (fullCode.length() > 1000000) {
              fullCode = fullCode.substring(0, 1000000) + "\n... (Codebase truncated due to size)";
          }
          projectKnowledgeContext += "\nExisting Project Source Code:\n" + fullCode + "\n";
        }
      } catch (Exception e) {
        log.warn("Failed to load project knowledge context for precheck", e);
      }
    }

    String prompt = """
        You are a senior software architect. Return ONLY valid JSON.
        Do not wrap the response in markdown.

        TASK: Analyze the existing project codebase and determine the implementation status of the requested feature.

        RULES:
        1. Read the 'Existing Project Source Code' carefully.
        2. Set 'analysisStatus' to "EXISTS" if the core functional components are already present.
        3. Set 'analysisStatus' to "PARTIAL" if major core requirements are missing but some exist.
        4. Set 'analysisStatus' to "FRESH" if no files relating to this feature exist.
        5. In 'suggestion', write a short developer note explaining what you found and what remains.

        Response Format:
        {
          "analysisStatus": "FRESH",
          "suggestion": "..."
        }

        """ + projectKnowledgeContext + "\nFeature:\n" + featureDescription;

    String aiResponse = aiClient.generateContent(prompt);

    try {
      String cleanedResponse = aiResponse.replace("```json", "").replace("```", "").trim();
      cleanedResponse = sanitizeJson(cleanedResponse);
      com.fasterxml.jackson.databind.ObjectMapper localMapper = objectMapper.copy()
          .configure(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
          .configure(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true);

      return localMapper.readValue(cleanedResponse, com.developerev.dto.PrecheckFeatureResponseDto.class);
    } catch (Exception e) {
      log.error("[AI_ERROR] Failed to parse precheck response", e);
      com.developerev.dto.PrecheckFeatureResponseDto fallback = new com.developerev.dto.PrecheckFeatureResponseDto();
      fallback.setAnalysisStatus("FRESH");
      fallback.setSuggestion("I couldn't confidently analyze the codebase. Let's build this feature from scratch.");
      return fallback;
    }
  }

  private String sanitizeJson(String json) {
    if (json == null) return null;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < json.length(); i++) {
        char c = json.charAt(i);
        if (c == '\\') {
            if (i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"' || next == '\\' || next == '/' || next == 'b' || next == 'f' || next == 'n' || next == 'r' || next == 't' || next == 'u') {
                    sb.append(c);
                    sb.append(next);
                    i++;
                } else {
                    sb.append("\\\\");
                }
            } else {
                sb.append("\\\\");
            }
        } else {
            sb.append(c);
        }
    }
    return sb.toString();
  }

  public ProjectPlanResponseDto generateProjectPlan(Long projectId, String featureDescription) {

    com.developerev.model.Project project = projectRepository.findById(projectId).orElse(null);

    // ── Auto-detect stack from cloned repo if fields are empty ─────────────────
    // This handles projects imported via Git clone with no manually filled fields.
    java.io.File repoDir = gitService.getRepoDir(projectId);
    if (project != null && (project.getLanguage() == null || project.getLanguage().isBlank())) {
      DetectedStack detected = detectStackFromRepo(repoDir, project);
      // Reload after persistence
      if (detected.hasLanguage()) {
        project = projectRepository.findById(projectId).orElse(project);
        log.info("[PLAN] Auto-detected stack for project {}: {} / {}", projectId, detected.language(), detected.framework());
      }
    }
    String stackContext = "";
    if (project != null && project.getLanguage() != null) {
      stackContext = String.format("""
          Project Stack Context:
          - Project Name: %s
          - Project Type: %s
          - Language: %s (version: %s)
          - Framework: %s (version: %s)
          - Database: %s (version: %s)
          - Selected Dependencies: %s
          %s
          """,
          project.getName(),
          project.getProjectType(),
          project.getLanguage(), project.getLanguageVersion(),
          project.getFramework(), project.getFrameworkVersion(),
          project.getDatabaseName(), project.getDatabaseVersion(),
          project.getDependencies(),
          (project.getAiBusinessContext() != null
              ? "- AI Business Understanding (scanned from codebase): " + project.getAiBusinessContext()
              : ""));
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

    // Gather codebase knowledge to figure out what is already implemented
    String projectKnowledgeContext = "";
    if (repoDir != null && repoDir.exists()) {
      try {
        String projectStructure = directoryScannerService.getProjectStructure(repoDir.toPath());
        if (projectStructure != null && !projectStructure.isEmpty()) {
          projectKnowledgeContext += "\nExisting Project File/Folder Structure:\n" + projectStructure + "\n";
          
          StringBuilder codebaseBuilder = new StringBuilder();
          java.nio.file.Files.walk(repoDir.toPath())
              .filter(java.nio.file.Files::isRegularFile)
              .filter(p -> {
                  String name = p.getFileName().toString().toLowerCase();
                  return name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".py") 
                         || name.endsWith(".php") || name.endsWith(".go") || name.endsWith(".js") || name.endsWith(".css") 
                         || name.endsWith(".html") || name.endsWith(".json") || name.endsWith(".xml");
              })
              .forEach(p -> {
                  try {
                      String content = java.nio.file.Files.readString(p);
                      codebaseBuilder.append("\n--- ").append(repoDir.toPath().relativize(p).toString().replace("\\", "/")).append(" ---\n");
                      codebaseBuilder.append(content).append("\n");
                  } catch (Exception ignored) {}
              });
          
          String fullCode = codebaseBuilder.toString();
          // Safety cutoff (1 Million chars is ~250k tokens, which is well within Gemini's 2M limit)
          if (fullCode.length() > 1000000) {
              fullCode = fullCode.substring(0, 1000000) + "\n... (Codebase truncated due to size)";
          }
          projectKnowledgeContext += "\nExisting Project Source Code:\n" + fullCode + "\n";
        }
      } catch (Exception e) {
        log.warn("Failed to load project knowledge context", e);
      }
    }

    // Build a hard mandatory language constraint line placed FIRST in the prompt
    // so the AI cannot drift to another language/framework
    String mandatoryStackLine = "";
    if (project != null && project.getLanguage() != null) {
      mandatoryStackLine = String.format(
          "MANDATORY: You MUST write ALL code EXCLUSIVELY in %s%s%s. "
          + "Do NOT generate code in any other language or framework under any circumstances. "
          + "Every file you produce must be a valid %s file.",
          project.getLanguage(),
          (project.getFramework() != null && !project.getFramework().isBlank() ? " with " + project.getFramework() : ""),
          (project.getLanguageVersion() != null && !project.getLanguageVersion().isBlank() ? " (version " + project.getLanguageVersion() + ")" : ""),
          project.getLanguage());
    }

    String prompt = mandatoryStackLine + """

        You are a senior software architect with deep knowledge of codebases.
        Return ONLY valid JSON.
        Do not wrap the response in markdown.
        Do not include ```json or ``` markers.
        No explanations. No markdown formatting.

        TASK: Analyze the existing project codebase thoroughly. Then produce a smart, context-aware implementation plan for the feature described below.

        ANALYSIS RULES:
        1. Read the 'Existing Project Source Code' section carefully.
        2. Determine if the requested feature is: NOT started (FRESH), partially started (PARTIAL), or mostly/fully done (EXISTS).
        3. Set 'analysisStatus' to one of: "FRESH", "PARTIAL", or "EXISTS".
        4. In 'suggestion', write a short, friendly developer note (2-4 sentences) explaining what you found in the existing code and what you recommend. Be warm and specific — e.g. "Hey! I noticed the payment controller already exists with Razorpay integration. The webhook handler and refund flow look incomplete though — the plan below covers only those remaining pieces."
        5. For FRESH: plan all tasks from scratch.
        6. For PARTIAL: plan ONLY the remaining/missing work. Do NOT repeat already completed work.
        7. For EXISTS: still produce a minimal task list (e.g. improvements, edge-case handling, tests) but make 'suggestion' clearly state the feature is largely done.
        8. ALL tasks must be designed for the project's exact language and framework specified above.
        9. Auto-detect all the specific libraries, dependencies, framework modules, or tools needed for this feature and return them in "detectedNeeds".
        10. CRITICAL: For the "type" field of each task, you MUST use exactly one of the following predefined strings: "Backend", "Frontend", "Database", "Security", "Testing", "Documentation", "DevOps", "Architecture", "Design". Do NOT invent custom types like "Backend Configuration" or "Database Schema". Use these predefined exact strings ONLY.

        Response Format:
        {
          "featureName": "",
          "complexity": "",
          "totalEstimatedHours": 0,
          "analysisStatus": "FRESH",
          "suggestion": "Short friendly message about what was found and what the plan covers.",
          "detectedNeeds": [
             "Dependency Name/Tool Name"
          ],
          "tasks": [
             {
               "title": "",
               "description": "",
               "type": "Backend", // MUST be one of the predefined types
               "estimatedHours": 0,
               "priority": ""
             }
          ]
        }

        """ + stackContext + linkedContext + projectKnowledgeContext + "\nFeature:\n" + featureDescription;



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

    // ── Auto-detect stack from cloned repo if fields are empty ─────────────────
    if (project != null && (project.getLanguage() == null || project.getLanguage().isBlank())) {
      java.io.File repoDir = gitService.getRepoDir(feature.getProjectId());
      DetectedStack detected = detectStackFromRepo(repoDir, project);
      if (detected.hasLanguage()) {
        project = projectRepository.findById(feature.getProjectId()).orElse(project);
        log.info("[IMPL] Auto-detected stack for project {}: {} / {}", feature.getProjectId(), detected.language(), detected.framework());
      }
    }
    String stackInfo = "";
    if (project != null && project.getLanguage() != null) {
      stackInfo = String.format("""
          Project Tech Stack Context:
          - Project Name: %s
          - Project Type: %s
          - Language: %s (version: %s)
          - Framework: %s (version: %s)
          - Database: %s (version: %s)
          - Base Stack Dependencies: %s
          %s
          """,
          project.getName(),
          project.getProjectType(),
          project.getLanguage(), project.getLanguageVersion(),
          project.getFramework(), project.getFrameworkVersion(),
          project.getDatabaseName(), project.getDatabaseVersion(),
          project.getDependencies(),
          (project.getAiBusinessContext() != null
              ? "- AI Business Understanding: " + project.getAiBusinessContext()
              : ""));
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

    // Build a hard mandatory language constraint placed FIRST in the prompt
    // so the AI cannot generate code in any other language
    String mandatoryImpl = "";
    if (project != null && project.getLanguage() != null) {
      mandatoryImpl = String.format(
          "MANDATORY: You MUST write ALL code EXCLUSIVELY in %s%s%s. "
          + "Do NOT generate any file in another language or framework. "
          + "Every single generated file must be a valid %s source file or config compatible with %s%s.",
          project.getLanguage(),
          (project.getFramework() != null && !project.getFramework().isBlank() ? " with " + project.getFramework() : ""),
          (project.getLanguageVersion() != null && !project.getLanguageVersion().isBlank() ? " (version " + project.getLanguageVersion() + ")" : ""),
          project.getLanguage(),
          project.getLanguage(),
          (project.getFramework() != null && !project.getFramework().isBlank() ? "/" + project.getFramework() : ""));
    }

    // 2. Build prompt
    String prompt = mandatoryImpl + """

        You are a senior full-stack developer. Your task is to implement the ACTUAL SOURCE CODE for a specific feature based on the plan, tech stack, existing project file/folder structure, and detected dependencies/needs provided.

        CRITICAL RULES — READ BEFORE GENERATING ANYTHING:
        1. Write ONLY in the language and framework specified in the MANDATORY constraint above.
        2. Every file extension must match that language (e.g. .java for Java, .ts/.tsx for TypeScript, .py for Python, .php for PHP, .go for Go).
        3. DO NOT output a single markdown (.md) file. You MUST generate separate, individual source code files. Markdown files are strictly forbidden unless it is explicitly a README update.
        4. DO NOT wrap the code in the 'content' field with markdown code blocks (e.g., ```java). The 'content' field must contain ONLY the raw, compilable/executable source code.
        5. If the project is Java/Spring Boot — write Java. Never write Python, PHP, JS, TS, etc.
        6. If the project is TypeScript/React — write TypeScript. Never write Java, PHP, etc.
        7. If the project is PHP/Laravel — write PHP. Never write Java, Python, TypeScript, etc.
        8. Align all generated file paths with the existing project structure shown below.
        9. If an existing file (pom.xml, build.gradle, package.json, composer.json, config) needs updating, use its exact path.

        Analyze the existing project file/folder structure below. Align any new or modified files perfectly with this structure. For example, if there is a module/subdirectory (e.g., `DEV--backend`) containing the project codebase, make sure the paths in the generated JSON are nested correctly inside that subdirectory (e.g., starting with `DEV--backend/src/...`) rather than at the root level, so they integrate perfectly.

        Return ONLY valid JSON.
        Do not wrap the response in markdown.
        Do not include ```json or ``` markers.
        No explanations. No markdown formatting.

        Response format must strictly be:
        {
          "files": [
            {
              "path": "relative/path/to/file.ext",
              "content": "file content matching the target language and framework code structure"
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
          .replace("\\'", "'")
          .trim();

      log.debug("Gemini implement plan response (cleaned): {}", cleanedResponse);

      // Use a local copy of objectMapper configured to allow unescaped control characters (e.g. newlines)
      ObjectMapper localMapper = objectMapper.copy()
          .configure(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true);
      
      JsonNode rootNode = localMapper.readTree(cleanedResponse);
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

      for (com.developerev.model.Task task : tasks) {
        task.setStatus(com.developerev.model.TaskStatus.DONE);
        taskRepository.save(task);
      }

      activityLogService.logCurrentUserActivity(feature.getProjectId(), "Implemented Plan", "AI implemented codebase for feature: " + feature.getName());

    } catch (JsonProcessingException e) {
      log.error("[AI_ERROR][PARSE_FAILURE] Failed to parse Gemini implement plan response: {}", e.getMessage(), e);
      throw new AiResponseParsingException("JSON parse failure in implementPlan. Error details: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("[AI_ERROR][UNEXPECTED] Unexpected error in implementPlan: {}", e.getMessage(), e);
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
