package com.developerev.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GeminiClient {

        @Value("${gemini.api.key}")
        private String apiKey;

        private final WebClient webClient = WebClient.builder()
                        .baseUrl("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
                        .build();

        public String callGemini(String prompt) {

                Map<String, Object> requestBody = Map.of(
                                "contents", List.of(
                                                Map.of("parts", List.of(Map.of("text", prompt)))));

                return webClient.post()
                                .uri("?key=" + apiKey)
                                .bodyValue(requestBody)
                                .retrieve()
                                .bodyToMono(String.class)
                                .block();
        }
}
