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
import com.developerev.exception.auth.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    // --- Authentication Exceptions ---

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleUserAlreadyExists(UserAlreadyExistsException ex) {
        return createErrorResponse(HttpStatus.CONFLICT, "User Conflict", ex);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return createErrorResponse(HttpStatus.UNAUTHORIZED, "Invalid Credentials", ex);
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailNotVerified(EmailNotVerifiedException ex) {
        return createErrorResponse(HttpStatus.FORBIDDEN, "Email Not Verified", ex);
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidVerificationToken(InvalidVerificationTokenException ex) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Invalid Token", ex);
    }

    @ExceptionHandler(VerificationTokenExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleVerificationTokenExpired(VerificationTokenExpiredException ex) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Token Expired", ex);
    }

    @ExceptionHandler(SamePasswordException.class)
    public ResponseEntity<ApiErrorResponse> handleSamePassword(SamePasswordException ex) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Invalid Password", ex);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return createErrorResponse(HttpStatus.NOT_FOUND, "User Not Found", ex);
    }


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
     * Handle Javax/Jakarta Validation Errors (@Valid)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        String userMessage = errors.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .reduce((acc, next) -> acc + ", " + next)
                .orElse("Validation failed");

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Error")
                .userMessage(userMessage)
                .debugMessage(ex.getMessage())
                .retryable(false)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
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
                .userMessage("An unexpected system error occurred.")
                .debugMessage(ex.getMessage())
                .retryable(false)
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<ApiErrorResponse> createErrorResponse(HttpStatus status, String error, RuntimeException ex) {
        log.warn("[AUTH_ERROR][{}] {}", error.toUpperCase().replace(' ', '_'), ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(status.value())
                .error(error)
                .userMessage(ex.getMessage())
                .debugMessage(ex.getClass().getSimpleName())
                .retryable(false)
                .build();
        return ResponseEntity.status(status).body(body);
    }

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

