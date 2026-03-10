package com.developerev.service;

import com.developerev.ai.exception.AiServiceUnavailableException;
import com.developerev.ai.exception.DailyQuotaExceededException;
import com.developerev.ai.exception.EmptyAiResponseException;
import com.developerev.ai.exception.InvalidApiKeyException;
import com.developerev.ai.exception.NetworkTimeoutException;
import com.developerev.ai.exception.RateLimitExceededException;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Low-level HTTP client for the Gemini Generative Language API.
 *
 * <p>Production features implemented:
 * <ul>
 *   <li>Connect timeout: 5 s, Read/Write timeout: 30 s</li>
 *   <li>HTTP error mapping → typed {@link com.developerev.ai.exception.GeminiApiException} subclasses</li>
 *   <li>Automatic retry (3 attempts, exponential back-off) for retryable errors (429, 500, 503, timeout)</li>
 *   <li>Null/empty response guard → {@link EmptyAiResponseException}</li>
 * </ul>
 */
@Slf4j
@Service
public class GeminiClient {

    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

    /** Maximum number of automatic retry attempts for retryable errors. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /** Initial back-off delay before the first retry. Doubles on each attempt. */
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(2);

    @Value("${gemini.api.key}")
    private String apiKey;

    private final WebClient webClient;

    public GeminiClient() {
        // ── Netty HttpClient with connect + read/write timeouts ───────────────
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * Calls the Gemini API with {@code prompt} and returns the raw JSON response string.
     *
     * @param prompt the user prompt to send
     * @return raw JSON response body from the API
     * @throws RateLimitExceededException    on HTTP 429
     * @throws DailyQuotaExceededException   when the daily quota resource is exhausted
     * @throws InvalidApiKeyException        on HTTP 401 / 403
     * @throws AiServiceUnavailableException on HTTP 500 / 503
     * @throws NetworkTimeoutException       on connect or read timeout
     * @throws EmptyAiResponseException      when the response body is null or blank
     */
    public String callGemini(String prompt) {

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))));

        try {
            String response = webClient.post()
                    .uri("?key=" + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    // ── Map HTTP error statuses to typed exceptions ────────────
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                        int code = clientResponse.statusCode().value();
                        return clientResponse.bodyToMono(String.class).map(body -> {
                            if (code == 429) {
                                // Distinguish rate-limit from daily quota by inspecting the body
                                if (body.contains("RATE_LIMIT_EXCEEDED") || body.contains("rateLimitExceeded")) {
                                    return new RateLimitExceededException(
                                            "Gemini HTTP 429 – rate limit. Body: " + body);
                                }
                                return new DailyQuotaExceededException(
                                        "Gemini HTTP 429 – quota exhausted. Body: " + body);
                            }
                            if (code == 401 || code == 403) {
                                return new InvalidApiKeyException(
                                        "Gemini HTTP " + code + " – invalid/missing API key. Body: " + body);
                            }
                            // Unknown 4xx — treat as service unavailable
                            return new AiServiceUnavailableException(
                                    "Gemini HTTP " + code + " client error. Body: " + body);
                        });
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, serverResponse -> {
                        int code = serverResponse.statusCode().value();
                        return serverResponse.bodyToMono(String.class).map(body ->
                                new AiServiceUnavailableException(
                                        "Gemini HTTP " + code + " server error. Body: " + body));
                    })
                    .bodyToMono(String.class)
                    // ── Retry: exponential back-off for retryable statuses ─────
                    .retryWhen(Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_BACKOFF)
                            .filter(this::isRetryable)
                            .doBeforeRetry(signal -> log.warn(
                                    "[AI_RETRY] Attempt {} after retryable error: {}",
                                    signal.totalRetries() + 1,
                                    signal.failure().getMessage())))
                    .block();

            // ── Guard: null / blank response ───────────────────────────────────
            if (response == null || response.isBlank()) {
                throw new EmptyAiResponseException(
                        "Gemini returned a null or empty response body for prompt (first 120 chars): "
                                + prompt.substring(0, Math.min(prompt.length(), 120)));
            }

            return response;

        } catch (WebClientRequestException ex) {
            // WebClientRequestException = connect or read timeout at the Netty level
            throw new NetworkTimeoutException(
                    "Gemini connection/read timeout: " + ex.getMessage(), ex);
        }
    }

    /**
     * Determines whether a {@link Throwable} is eligible for an automatic retry.
     * Only our retryable typed exceptions qualify; all others propagate immediately.
     */
    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof com.developerev.ai.exception.GeminiApiException ex) {
            return ex.isRetryable();
        }
        return false;
    }
}

