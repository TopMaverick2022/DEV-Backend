package com.developerev.ai.provider.impl;

import com.developerev.ai.dto.ProviderResponse;
import com.developerev.ai.prompt.PromptType;
import com.developerev.ai.provider.LLMProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiProviderImpl implements LLMProvider {

    private final RestTemplate restTemplate;

    @Value("${ai.gemini.api-key}")
    private String geminiApiKey;

    @Value("${ai.gemini.model:gemini-2.5-flash}")
    private String model;

    public static final String PROVIDER_NAME = "GEMINI";

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderResponse generateContent(PromptType promptType, String userContext) {
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }

        String fullUrl = "https://generativelanguage.googleapis.com/v1/models/"
                + model
                + ":generateContent?key="
                + geminiApiKey;

        // Gemini 2.x REST expects a chat-style format where instructions are passed as
        // user content
        String finalPrompt = promptType.getSystemPrompt() + "\n\n" + userContext;

        // Gemini REST Payload format
        Map<String, Object> requestBody = new HashMap<>();

        // User payload
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("parts", Collections.singletonList(Map.of("text", finalPrompt)));
        requestBody.put("contents", Collections.singletonList(userMessage));

        // Adjust parameters
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", promptType.getRecommendedTemperature());
        requestBody.put("generationConfig", generationConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("Calling Gemini API -> URL: {} | Model: {}", fullUrl, model);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    fullUrl,
                    HttpMethod.POST,
                    entity,
                    Map.class);

            return parseGeminiResponse(response.getBody());

        } catch (HttpClientErrorException e) {
            log.error("Gemini API Client Error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 401) {
                throw new IllegalStateException("Invalid Gemini API Key provided.");
            } else if (e.getStatusCode().value() == 429) {
                throw new com.developerev.ai.exception.TokenLimitExceededException("Provider quota exceeded (429).");
            }
            throw new RuntimeException("AI provider failed to generate response: " + e.getMessage());

        } catch (HttpServerErrorException e) {
            log.error("Gemini API Server Error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 503) {
                throw new com.developerev.ai.exception.ProviderTimeoutException(
                        "Gemini Provider is currently unavailable (503).", e);
            }
            throw new RuntimeException("AI provider internal error: " + e.getMessage());

        } catch (Exception e) {
            log.error("Unexpected error calling Gemini API", e);
            throw new RuntimeException("Unexpected error during AI generation", e);
        }
    }

    private ProviderResponse parseGeminiResponse(Map<String, Object> responseBody) {
        if (responseBody == null || !responseBody.containsKey("candidates")) {
            throw new RuntimeException("Invalid response from Gemini Provider");
        }

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
        if (candidates.isEmpty()) {
            throw new RuntimeException("No candidates returned from Gemini");
        }

        Map<String, Object> firstCandidate = candidates.get(0);
        Map<String, Object> content = (Map<String, Object>) firstCandidate.get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        String finalOutput = (String) parts.get(0).get("text");

        // Parse Token Usage Metadata
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;

        if (responseBody.containsKey("usageMetadata")) {
            Map<String, Integer> usage = (Map<String, Integer>) responseBody.get("usageMetadata");
            promptTokens = usage.getOrDefault("promptTokenCount", 0);
            completionTokens = usage.getOrDefault("candidatesTokenCount", 0);
            totalTokens = usage.getOrDefault("totalTokenCount", 0);
        }

        return new ProviderResponse(finalOutput, promptTokens, completionTokens, totalTokens, getProviderName());
    }
}
