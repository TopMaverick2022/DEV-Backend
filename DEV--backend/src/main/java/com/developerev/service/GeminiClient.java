package com.developerev.service;

import com.developerev.ai.exception.AiServiceUnavailableException;
import com.developerev.ai.exception.DailyQuotaExceededException;
import com.developerev.ai.exception.EmptyAiResponseException;
import com.developerev.ai.exception.InvalidApiKeyException;
import com.developerev.ai.exception.NetworkTimeoutException;
import com.developerev.ai.exception.RateLimitExceededException;
import com.developerev.ai.exception.GeminiApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Low-level HTTP client for the Gemini Generative Language API.
 * Implements AiClient to return extracted text.
 * Supports multiple API keys with automatic rotation on quota/rate limits.
 */
@Slf4j
@Service("geminiClient")
public class GeminiClient implements AiClient {

    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final WebClient webClient;
    private final ObjectMapper tokenMapper = new ObjectMapper();
    
    private final List<String> apiKeys;
    private final AtomicInteger currentKeyIndex = new AtomicInteger(0);

    public GeminiClient(
            @Value("${ai.gemini.keys:${gemini.api.key:}}") String keysConfig) {
        
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(180, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(180, TimeUnit.SECONDS)));

        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
                
        // Debug: Log all environment variables starting with GEMINI to see what the JVM sees
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("GEMINI")) {
                log.info("[DEBUG_ENV] Found Env Var: {} = {} (Length: {})", k, k.equals("GEMINI_API_KEYS") ? "[HIDDEN_LIST]" : v, v.length());
            }
        });

        // Parse comma-separated keys
        if (keysConfig != null && !keysConfig.isBlank()) {
            this.apiKeys = Arrays.stream(keysConfig.split(","))
                    .map(String::trim)
                    .filter(k -> !k.isEmpty())
                    .toList();
            log.info("GeminiClient initialized with {} API keys.", this.apiKeys.size());
        } else {
            this.apiKeys = new ArrayList<>();
            log.warn("No Gemini API keys configured! Please set GEMINI_API_KEYS environment variable.");
        }
    }

    private String getNextKey() {
        if (apiKeys.isEmpty()) {
            throw new InvalidApiKeyException("No Gemini API keys available in configuration.");
        }
        return apiKeys.get(currentKeyIndex.get() % apiKeys.size());
    }
    
    private void rotateKey() {
        if (apiKeys.size() > 1) {
            int newIndex = currentKeyIndex.incrementAndGet();
            log.warn("[AI_KEY_ROTATION] Switched to Gemini API key index {} (of {})", 
                    (newIndex % apiKeys.size()) + 1, apiKeys.size());
        }
    }

    @Override
    public String generateContent(String prompt) {
        if (apiKeys.isEmpty()) {
            throw new InvalidApiKeyException("No API keys configured");
        }

        // Try up to the number of keys we have if we hit quota errors
        int maxAttempts = apiKeys.size();
        for (int i = 0; i < Math.max(1, maxAttempts); i++) {
            try {
                return executeRequest(prompt);
            } catch (DailyQuotaExceededException | RateLimitExceededException e) {
                log.error("[AI_QUOTA] Key {} failed due to quota/rate limits: {}", currentKeyIndex.get() % apiKeys.size(), e.getMessage());
                if (i < maxAttempts - 1) {
                    rotateKey();
                } else {
                    log.error("[AI_QUOTA] All Gemini keys exhausted.");
                    throw e; // Let FallbackAiService handle it
                }
            } catch (GeminiApiException e) {
                // If it bubbles up here, the internal WebClient retries are exhausted.
                // For any retryable error (503, timeout) OR an invalid key, try the next key.
                boolean canRotate = (e.isRetryable() || e instanceof InvalidApiKeyException) && i < maxAttempts - 1;
                if (canRotate) {
                    log.warn("[AI_KEY_ROTATION] Key index {} failed with {} ({}). Rotating to next key.",
                            currentKeyIndex.get() % apiKeys.size(),
                            e.getClass().getSimpleName(),
                            e.getMessage());
                    rotateKey();
                } else {
                    throw e;
                }
            }
        }
        throw new AiServiceUnavailableException("Failed to generate content after trying all keys");
    }

    private String executeRequest(String prompt) {
        int keyIndex = currentKeyIndex.get() % apiKeys.size();
        log.info("[AI_REQUEST] Using Gemini API key {} of {}...", (keyIndex + 1), apiKeys.size());
        String currentKey = getNextKey();
        
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))));

        try {
            String response = webClient.post()
                    .uri("?key=" + currentKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                        int code = clientResponse.statusCode().value();
                        return clientResponse.bodyToMono(String.class).map(body -> {
                            if (code == 429) {
                                if (body.contains("RATE_LIMIT_EXCEEDED") || body.contains("rateLimitExceeded")) {
                                    return new RateLimitExceededException("Gemini HTTP 429 – rate limit. Body: " + body);
                                }
                                return new DailyQuotaExceededException("Gemini HTTP 429 – quota exhausted. Body: " + body);
                            }
                            if (code == 401 || code == 403) {
                                return new InvalidApiKeyException("Gemini HTTP " + code + " – invalid/missing API key. Body: " + body);
                            }
                            return new AiServiceUnavailableException("Gemini HTTP " + code + " client error. Body: " + body);
                        });
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, serverResponse -> {
                        int code = serverResponse.statusCode().value();
                        return serverResponse.bodyToMono(String.class).map(body ->
                                new AiServiceUnavailableException("Gemini HTTP " + code + " server error. Body: " + body));
                    })
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_BACKOFF)
                            .maxBackoff(MAX_BACKOFF)
                            .filter(this::isRetryable)
                            .doBeforeRetry(signal -> log.warn(
                                    "[AI_RETRY] Attempt {}/{} after retryable error: {}",
                                    signal.totalRetries() + 1,
                                    MAX_RETRY_ATTEMPTS,
                                    signal.failure().getMessage())))
                    .block();

            if (response == null || response.isBlank()) {
                throw new EmptyAiResponseException(
                        "Gemini returned a null or empty response body for prompt (first 120 chars): "
                                + prompt.substring(0, Math.min(prompt.length(), 120)));
            }

            try {
                JsonNode root = tokenMapper.readTree(response);
                long tokens = root.path("usageMetadata").path("totalTokenCount").asLong(0);
                if (tokens > 0) {
                    log.debug("[AI_USAGE] Prompt tokens: {}, Response tokens: {}, Total: {}", 
                            root.path("usageMetadata").path("promptTokenCount").asLong(0),
                            root.path("usageMetadata").path("candidatesTokenCount").asLong(0),
                            tokens);
                }
                
                // Extract the text content from the Gemini JSON envelope
                return root.path("candidates").get(0)
                        .path("content")
                        .path("parts").get(0)
                        .path("text").asText();
                        
            } catch (Exception e) {
                log.error("Failed to parse Gemini JSON envelope. Raw: {}", response.substring(0, Math.min(response.length(), 200)));
                throw new RuntimeException("Failed to extract text from Gemini response", e);
            }

        } catch (WebClientRequestException ex) {
            throw new NetworkTimeoutException("Gemini connection/read timeout: " + ex.getMessage(), ex);
        }
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof GeminiApiException ex) {
            return ex.isRetryable();
        }
        return false;
    }
    
    // Kept for backward compatibility during refactoring if needed by other classes
    @Deprecated
    public String callGemini(String prompt) {
        return generateContent(prompt);
    }
}
