package com.knowsource.web;

import java.io.IOException;
import java.time.Instant;

import com.knowsource.chat.LlmUnavailableException;
import com.knowsource.document.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final int BAD_REQUEST = 40001;
    private static final int UNAUTHORIZED = 40100;
    private static final int FORBIDDEN = 40300;
    private static final int NOT_FOUND = 40400;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return error(HttpStatus.UNAUTHORIZED, UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingAuthentication(AuthenticationCredentialsNotFoundException ex) {
        return error(HttpStatus.UNAUTHORIZED, UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> handleSourceReadFailure(IOException ex) {
        return error(HttpStatus.NOT_FOUND, NOT_FOUND, "Document source not found.");
    }

    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleLlmUnavailable(LlmUnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, LlmUnavailableException.ERROR_CODE, ex.getMessage());
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, Instant.now()));
    }
}
