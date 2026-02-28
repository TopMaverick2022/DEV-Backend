package com.developerev.ai.exception;

public class ProviderTimeoutException extends RuntimeException {
    public ProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
