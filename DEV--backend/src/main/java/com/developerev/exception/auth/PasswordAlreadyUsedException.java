package com.developerev.exception.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PasswordAlreadyUsedException extends RuntimeException {
    public PasswordAlreadyUsedException(String message) {
        super(message);
    }
}
