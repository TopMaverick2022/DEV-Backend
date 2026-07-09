const fs = require('fs');
const path = 'd:/xampp/htdocs/DeveloperEv/DEV-Backend/DEV--backend/src/main/java/com/developerev/service/AntiGravityService.java';
let content = fs.readFileSync(path, 'utf8');

// Replace standard parsing with sanitized parsing
const replacementRegex = /String cleanedResponse = aiResponse[\s\S]*?\.trim\(\);[\s\S]*?(?:(?:ObjectMapper\s+localMapper\s*=\s*objectMapper\.copy\(\)[\s\S]*?;\s*)?(?:ProjectPlanResponseDto responseDto|JsonNode rootNode|SprintAiResponseDto dtoResponse|DependencyAiResponseDto dtoResponse|ArchitectureResponseDto|DatabaseSchemaResponseDto|List<com\.developerev\.dto\.RecommendedDependencyDto>|com\.developerev\.dto\.PrecheckFeatureResponseDto)\s*[a-zA-Z0-9_]*\s*=\s*(?:objectMapper|localMapper)\.(?:readValue|readTree)\(cleanedResponse[^\)]*\);|return (?:objectMapper|localMapper)\.readValue\(cleanedResponse[^\)]*\);)/g;

let matches = content.match(replacementRegex);
if(matches) {
    for(let match of matches) {
        // Skip precheckProjectFeature if it already has it
        if(match.includes('ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER') && match.includes('sanitizeJson')) continue;

        let returnVarType = "";
        let returnStatement = "";
        let readMethod = "";
        let clazz = "";
        
        if (match.includes("ProjectPlanResponseDto responseDto")) { returnStatement = "ProjectPlanResponseDto responseDto = "; readMethod = "readValue"; clazz = ", ProjectPlanResponseDto.class"; }
        else if (match.includes("JsonNode rootNode = ")) { returnStatement = "JsonNode rootNode = "; readMethod = "readTree"; clazz = ""; }
        else if (match.includes("SprintAiResponseDto dtoResponse")) { returnStatement = "SprintAiResponseDto dtoResponse = "; readMethod = "readValue"; clazz = ", SprintAiResponseDto.class"; }
        else if (match.includes("DependencyAiResponseDto dtoResponse")) { returnStatement = "DependencyAiResponseDto dtoResponse = "; readMethod = "readValue"; clazz = ", DependencyAiResponseDto.class"; }
        else if (match.includes("ArchitectureResponseDto.class")) { returnStatement = "return "; readMethod = "readValue"; clazz = ", ArchitectureResponseDto.class"; }
        else if (match.includes("DatabaseSchemaResponseDto.class")) { returnStatement = "return "; readMethod = "readValue"; clazz = ", DatabaseSchemaResponseDto.class"; }
        else if (match.includes("TypeReference<List<com.developerev.dto.RecommendedDependencyDto>>")) { returnStatement = "return "; readMethod = "readValue"; clazz = ", new com.fasterxml.jackson.core.type.TypeReference<List<com.developerev.dto.RecommendedDependencyDto>>() {}"; }

        if (!readMethod) continue;

        let newBlock = `String cleanedResponse = aiResponse
          .replace("\`\`\`json", "")
          .replace("\`\`\`", "")
          .replace("\\\\'", "'")
          .trim();
      
      cleanedResponse = sanitizeJson(cleanedResponse);

      com.fasterxml.jackson.databind.ObjectMapper localMapper = objectMapper.copy()
          .configure(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
          .configure(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true);
          
      ${returnStatement}localMapper.${readMethod}(cleanedResponse${clazz});`;

        content = content.replace(match, newBlock);
    }
}

fs.writeFileSync(path, content, 'utf8');
console.log('Successfully patched JSON parsing in AntiGravityService');
