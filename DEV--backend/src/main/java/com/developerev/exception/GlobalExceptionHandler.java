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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import reactor.core.Exceptions;

import jakarta.persistence.EntityNotFoundException;
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

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex) {
        return createErrorResponse(HttpStatus.NOT_FOUND, "Account Not Found", ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return createErrorResponse(HttpStatus.UNAUTHORIZED, "Invalid Credentials", "Incorrect username or password. Please try again.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return createErrorResponse(HttpStatus.FORBIDDEN, "Access Denied", "You do not have permission to perform this action.");
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

    /**
     * Handles reactor's RetryExhaustedException, which wraps the original typed exception
     * after all retry attempts are exhausted (e.g., after 3x retries on a Gemini 503).
     * Unwraps the root cause and re-routes to the appropriate specific handler.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleRetryExhausted(Exception ex) {
        // Unwrap RetryExhaustedException to get the real cause
        Throwable cause = ex;
        if (Exceptions.isRetryExhausted(ex) && ex.getCause() != null) {
            cause = ex.getCause();
            log.warn("[AI_ERROR][RETRY_EXHAUSTED] All retries failed. Root cause: {}", cause.getMessage());
        }

        if (cause instanceof AiServiceUnavailableException e) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, "AI Service Unavailable (Retries Exhausted)", e);
        }
        if (cause instanceof RateLimitExceededException e) {
            return response(HttpStatus.TOO_MANY_REQUESTS, "Rate Limit Exceeded", e);
        }
        if (cause instanceof DailyQuotaExceededException e) {
            return response(HttpStatus.TOO_MANY_REQUESTS, "Daily Quota Exceeded", e);
        }
        if (cause instanceof NetworkTimeoutException e) {
            return response(HttpStatus.GATEWAY_TIMEOUT, "Network Timeout", e);
        }
        if (cause instanceof GeminiApiException gae) {
            log("[AI_ERROR][UNCLASSIFIED]", gae);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, "AI Error", gae);
        }

        // Truly unexpected — log fully
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

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String msg = "Database constraint violation. Please ensure all data is unique and valid.";
        if (ex.getMessage() != null && ex.getMessage().contains("Duplicate entry")) {
            msg = "This record already exists in our system.";
        }
        return createErrorResponse(HttpStatus.CONFLICT, "Database Error", msg);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleEntityNotFound(EntityNotFoundException ex) {
        return createErrorResponse(HttpStatus.NOT_FOUND, "Resource Not Found", "The requested item could not be found.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParams(MissingServletRequestParameterException ex) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Missing Parameter", "Required parameter '" + ex.getParameterName() + "' is missing.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Invalid Format", "Invalid value format for parameter '" + ex.getName() + "'.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return createErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "The HTTP method '" + ex.getMethod() + "' is not supported for this endpoint.");
    }

    /**
     * Catch-all handler for any {@link GeminiApiException} subclass not matched by the
     * more specific typed handlers above.
     */
    @ExceptionHandler(GeminiApiException.class)
    public ResponseEntity<ApiErrorResponse> handleGeminiApiException(GeminiApiException ex) {
        log("[AI_ERROR][UNCLASSIFIED]", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "AI Error", ex);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<ApiErrorResponse> createErrorResponse(HttpStatus status, String error, RuntimeException ex) {
        return createErrorResponse(status, error, ex.getMessage());
    }

    private ResponseEntity<ApiErrorResponse> createErrorResponse(HttpStatus status, String error, String userMessage) {
        log.warn("[CLIENT_ERROR][{}] {}", error.toUpperCase().replace(' ', '_'), userMessage);
        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(status.value())
                .error(error)
                .userMessage(userMessage)
                .debugMessage(status.getReasonPhrase())
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

