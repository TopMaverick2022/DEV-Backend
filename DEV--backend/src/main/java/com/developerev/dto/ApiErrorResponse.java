package com.developerev.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * Structured API error response body returned by {@code GlobalExceptionHandler}.
 *
 * <p>All fields are safe to expose to API consumers. The {@code debugMessage}
 * field is excluded when null to keep error responses clean for external clients
 * that should receive only the {@code userMessage}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {

    private final int status;
    private final String error;
    private final String userMessage;
    private final String debugMessage;
    private final boolean retryable;
    private final LocalDateTime timestamp;

    private ApiErrorResponse(Builder builder) {
        this.status       = builder.status;
        this.error        = builder.error;
        this.userMessage  = builder.userMessage;
        this.debugMessage = builder.debugMessage;
        this.retryable    = builder.retryable;
        this.timestamp    = LocalDateTime.now();
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public int           getStatus()       { return status; }
    public String        getError()        { return error; }
    public String        getUserMessage()  { return userMessage; }
    public String        getDebugMessage() { return debugMessage; }
    public boolean       isRetryable()     { return retryable; }
    public LocalDateTime getTimestamp()    { return timestamp; }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int     status;
        private String  error;
        private String  userMessage;
        private String  debugMessage;
        private boolean retryable;

        public Builder status(int status)             { this.status = status; return this; }
        public Builder error(String error)            { this.error = error; return this; }
        public Builder userMessage(String msg)        { this.userMessage = msg; return this; }
        public Builder debugMessage(String msg)       { this.debugMessage = msg; return this; }
        public Builder retryable(boolean retryable)   { this.retryable = retryable; return this; }
        public ApiErrorResponse build()               { return new ApiErrorResponse(this); }
    }
}
