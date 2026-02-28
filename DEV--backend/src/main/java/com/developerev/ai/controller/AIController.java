package com.developerev.ai.controller;

import com.developerev.ai.dto.CodeReviewRequest;
import com.developerev.ai.dto.CodeReviewResponse;
import com.developerev.ai.exception.ProviderTimeoutException;
import com.developerev.ai.exception.TokenLimitExceededException;
import com.developerev.ai.exception.UnsupportedProviderException;
import com.developerev.ai.prompt.PromptType;
import com.developerev.ai.service.AIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AIController {

    private final AIService aiService;

    @PostMapping("/review")
    public ResponseEntity<CodeReviewResponse> submitCodeReview(
            @RequestHeader(value = "X-Provider", defaultValue = "GEMINI") String providerName,
            @RequestBody CodeReviewRequest request) {

        log.info("Received code review request for userId: {} using provider: {}", request.getUserId(), providerName);

        try {
            // Business logic is fully delegated to AIService.
            String aiResult = aiService.askAI(
                    PromptType.CODE_REVIEW,
                    request.getDiff(),
                    providerName,
                    request.getUserId());

            CodeReviewResponse response = CodeReviewResponse.builder()
                    .success(true)
                    .data(aiResult)
                    .providerUsed(providerName)
                    .timestamp(LocalDateTime.now())
                    .build();

            return ResponseEntity.ok(response);

        } catch (UnsupportedProviderException e) {
            log.warn("Invalid provider requested: {}", providerName);
            return buildErrorResponse(e.getMessage(), providerName, HttpStatus.BAD_REQUEST);

        } catch (TokenLimitExceededException e) {
            log.warn("Rate limit exceeded for userId: {}", request.getUserId());
            return buildErrorResponse(e.getMessage(), providerName, HttpStatus.TOO_MANY_REQUESTS);

        } catch (ProviderTimeoutException e) {
            log.error("Provider {} timed out during request.", providerName, e);
            return buildErrorResponse("The AI provider timed out. Please try again later.", providerName,
                    HttpStatus.GATEWAY_TIMEOUT);

        } catch (Exception e) {
            log.error("Unexpected error during AI code review processing", e);
            return buildErrorResponse("An internal server error occurred.", providerName,
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ResponseEntity<CodeReviewResponse> buildErrorResponse(String errorMessage, String providerName,
            HttpStatus status) {
        CodeReviewResponse response = CodeReviewResponse.builder()
                .success(false)
                .error(errorMessage)
                .providerUsed(providerName)
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(status).body(response);
    }
}
