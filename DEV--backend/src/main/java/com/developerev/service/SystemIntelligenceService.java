package com.developerev.service;

import com.developerev.dto.SystemQueryResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemIntelligenceService {

    private final ZipExtractorService zipExtractorService;
    private final DirectoryScannerService directoryScannerService;
    private final FileContentService fileContentService;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public SystemQueryResponseDto answerSystemQuery(MultipartFile zipFile, String query) {
        log.info("System Query received: {} for file {}", query, zipFile.getOriginalFilename());

        Path tempDir = null;
        try {
            tempDir = zipExtractorService.extractZip(zipFile);
            List<Path> sourceFiles = directoryScannerService.scan(tempDir);

            // Concatenate entire codebase, limiting by realistic tokens (~1M characters max to prevent payload rejection)
            StringBuilder codebaseBuilder = new StringBuilder();
            List<String> analyzedFiles = new ArrayList<>();
            int maxChars = 2_000_000; // Roughly ~500k tokens
            int currentChars = 0;

            for (Path file : sourceFiles) {
                if (currentChars >= maxChars) {
                    log.warn("Codebase size exceeded limit, truncating remaining files.");
                    break;
                }

                String filename = tempDir.relativize(file).toString().replace("\\", "/");
                String content = fileContentService.readFile(file);
                
                if (!content.isBlank()) {
                    codebaseBuilder.append("=== FILE: ").append(filename).append(" ===\n");
                    codebaseBuilder.append(content).append("\n\n");
                    analyzedFiles.add(filename);
                    currentChars += content.length();
                }
            }

            String prompt = """
                You are a senior Software Architect and Full Codebase Intelligence Engine.
                
                Analyze the entire provided codebase context below and answer the user's system-level query logically.
                Trace the data flow across multiple files, methods, and database interactions if necessary.
                
                Return ONLY valid JSON. Complete JSON object format with no markdown tags.
                
                Response format:
                {
                  "answer": "Detailed explanation outlining exactly how the codebase fulfills this, citing specific files and functions."
                }
                
                System Query: %s
                
                CODEBASE CONTEXT:
                %s
                """.formatted(query, codebaseBuilder.toString());

            log.info("Calling Gemini for System Query. Payload length: {}", prompt.length());
            String geminiResponse = geminiClient.callGemini(prompt);

            JsonNode root = objectMapper.readTree(geminiResponse);
            String textContent = root.path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            String cleanedResponse = textContent
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            SystemQueryResponseDto dto = objectMapper.readValue(cleanedResponse, SystemQueryResponseDto.class);
            dto.setAnalyzedFiles(analyzedFiles);
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("d:/DeveloperEV/Dev-Backend/DEV--backend/debug_files.txt"), 
                    "Analyzed files count: " + analyzedFiles.size() + "\nFiles: " + analyzedFiles
                );
            } catch (Exception ex) {
                log.error("Failed to write debug file", ex);
            }
            return dto;

        } catch (Exception e) {
            log.error("[AI_ERROR] Failed to execute full system query", e);
            throw new RuntimeException("Failed to process system query: " + e.getMessage(), e);
        } finally {
            if (tempDir != null) {
                zipExtractorService.cleanup(tempDir);
            }
        }
    }
}
