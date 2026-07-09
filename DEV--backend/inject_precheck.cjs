const fs = require('fs');
const path = 'd:/xampp/htdocs/DeveloperEv/DEV-Backend/DEV--backend/src/main/java/com/developerev/service/AntiGravityService.java';
let content = fs.readFileSync(path, 'utf8');

const injectionPoint = '  public ProjectPlanResponseDto generateProjectPlan(Long projectId, String featureDescription) {';

const methodCode = `
  public com.developerev.dto.PrecheckFeatureResponseDto precheckProjectFeature(Long projectId, String featureDescription) {
    java.io.File repoDir = gitService.getRepoDir(projectId);
    String projectKnowledgeContext = "";
    if (repoDir != null && repoDir.exists()) {
      try {
        String projectStructure = directoryScannerService.getProjectStructure(repoDir.toPath());
        if (projectStructure != null && !projectStructure.isEmpty()) {
          projectKnowledgeContext += "\\nExisting Project File/Folder Structure:\\n" + projectStructure + "\\n";
          
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
                      codebaseBuilder.append("\\n--- ").append(repoDir.toPath().relativize(p).toString().replace("\\\\", "/")).append(" ---\\n");
                      codebaseBuilder.append(content).append("\\n");
                  } catch (Exception ignored) {}
              });
          
          String fullCode = codebaseBuilder.toString();
          if (fullCode.length() > 1000000) {
              fullCode = fullCode.substring(0, 1000000) + "\\n... (Codebase truncated due to size)";
          }
          projectKnowledgeContext += "\\nExisting Project Source Code:\\n" + fullCode + "\\n";
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

        """ + projectKnowledgeContext + "\\nFeature:\\n" + featureDescription;

    String aiResponse = aiClient.generateContent(prompt);

    try {
      String cleanedResponse = aiResponse.replace("\`\`\`json", "").replace("\`\`\`", "").trim();
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

  public ProjectPlanResponseDto generateProjectPlan(Long projectId, String featureDescription) {`;

content = content.replace(injectionPoint, methodCode);
fs.writeFileSync(path, content, 'utf8');
console.log('Successfully injected precheckProjectFeature');
