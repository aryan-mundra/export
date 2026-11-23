package com.bnpp.export.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class of the module's own errors: each one carries the HTTP status it is answered with.
 * {@link GlobalExceptionHandler} turns it into {@code {"message": "..."}} with that status.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
