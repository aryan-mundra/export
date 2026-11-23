package com.bnpp.export.exception;

import org.springframework.http.HttpStatus;

/** The request is not valid: a missing or wrong value, or a rule it breaks (400). */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
