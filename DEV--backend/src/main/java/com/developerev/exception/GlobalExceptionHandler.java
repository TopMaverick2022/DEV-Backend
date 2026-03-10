package com.developerev.exception;

import com.developerev.ai.exception.AiResponseParsingException;
import com.developerev.ai.exception.AiServiceUnavailableException;
import com.developerev.ai.exception.DailyQuotaExceededException;
import com.developerev.ai.exception.EmptyAiResponseException;
import com.developerev.ai.exception.FileAnalysisException;
import com.developerev.ai.exception.GeminiApiException;
import com.developerev.ai.exception.InvalidApiKeyException;
import com.developerev.ai.exception.NetworkTimeoutException;
import com.developerev.ai.exception.RateLimitExceededException;
import com.developerev.ai.exception.UnexpectedAiException;
import com.developerev.ai.exception.UnsupportedLanguageException;
import com.developerev.dto.ApiErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Centralized exception handler for all API endpoints.
 *
 * <p>Mapping strategy:
 * <ul>
 *   <li>All {@link GeminiApiException} subclasses → typed HTTP status + {@link ApiErrorResponse}</li>
 *   <li>Any other uncaught {@link Exception} → HTTP 500 (last-resort fallback)</li>
 * </ul>
 *
 * <p>Every handler logs a structured {@code [AI_ERROR]} line with the debug message
 * so that server logs stay actionable without leaking internal details to API consumers.
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    // ── 429 Too Many Requests ──────────────────────────────────────────────────

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimit(RateLimitExceededException ex) {
        log("[AI_ERROR][RATE_LIMIT]", ex);
        return response(HttpStatus.TOO_MANY_REQUESTS, "Rate Limit Exceeded", ex);
    }

    @ExceptionHandler(DailyQuotaExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleDailyQuota(DailyQuotaExceededException ex) {
        log("[AI_ERROR][DAILY_QUOTA]", ex);
        return response(HttpStatus.TOO_MANY_REQUESTS, "Daily Quota Exceeded", ex);
    }

    // ── 422 Unprocessable Entity ───────────────────────────────────────────────

    @ExceptionHandler(UnsupportedLanguageException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedLanguage(UnsupportedLanguageException ex) {
        log("[AI_ERROR][UNSUPPORTED_LANGUAGE]", ex);
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported Language", ex);
    }

    // ── 502 Bad Gateway ───────────────────────────────────────────────────────

    @ExceptionHandler(AiResponseParsingException.class)
    public ResponseEntity<ApiErrorResponse> handleParsing(AiResponseParsingException ex) {
        log("[AI_ERROR][PARSE_FAILURE]", ex);
        return response(HttpStatus.BAD_GATEWAY, "AI Response Parsing Error", ex);
    }

    @ExceptionHandler(EmptyAiResponseException.class)
    public ResponseEntity<ApiErrorResponse> handleEmptyResponse(EmptyAiResponseException ex) {
        log("[AI_ERROR][EMPTY_RESPONSE]", ex);
        return response(HttpStatus.BAD_GATEWAY, "Empty AI Response", ex);
    }

    // ── 503 Service Unavailable ────────────────────────────────────────────────

    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleServiceUnavailable(AiServiceUnavailableException ex) {
        log("[AI_ERROR][SERVICE_UNAVAILABLE]", ex);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "AI Service Unavailable", ex);
    }

    // ── 504 Gateway Timeout ────────────────────────────────────────────────────

    @ExceptionHandler(NetworkTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleTimeout(NetworkTimeoutException ex) {
        log("[AI_ERROR][NETWORK_TIMEOUT]", ex);
        return response(HttpStatus.GATEWAY_TIMEOUT, "Network Timeout", ex);
    }

    // ── 500 Internal Server Error ─────────────────────────────────────────────

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidKey(InvalidApiKeyException ex) {
        // Intentionally 500: invalid API key is an internal config issue, not a client error
        log("[AI_ERROR][INVALID_API_KEY]", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Authentication Failure", ex);
    }

    @ExceptionHandler(FileAnalysisException.class)
    public ResponseEntity<ApiErrorResponse> handleFileAnalysis(FileAnalysisException ex) {
        log("[AI_ERROR][FILE_ANALYSIS]", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "File Analysis Failure", ex);
    }

    @ExceptionHandler(UnexpectedAiException.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(UnexpectedAiException ex) {
        log("[AI_ERROR][UNEXPECTED]", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected AI Error", ex);
    }

    /**
     * Catch-all handler for any {@link GeminiApiException} subclass not matched above,
     * and for any other unhandled {@link Exception}.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleAllExceptions(Exception ex) {
        if (ex instanceof GeminiApiException gae) {
            log("[AI_ERROR][UNCLASSIFIED]", gae);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, "AI Error", gae);
        }
        log.error("[SYSTEM_ERROR] Unhandled exception: {}", ex.getMessage(), ex);
        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .userMessage("An unexpected system error occurred during AI analysis.")
                .debugMessage(ex.getMessage())
                .retryable(false)
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private void log(String prefix, GeminiApiException ex) {
        log.error("{} retryable={} | debug: {}", prefix, ex.isRetryable(), ex.getDebugMessage(), ex);
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String error, GeminiApiException ex) {
        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(status.value())
                .error(error)
                .userMessage(ex.getUserMessage())
                .debugMessage(ex.getDebugMessage())
                .retryable(ex.isRetryable())
                .build();
        return ResponseEntity.status(status).body(body);
    }
}

