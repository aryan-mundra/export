package com.bnpp.export.exception;

import org.springframework.http.HttpStatus;

/** The request clashes with the current state: a duplicate, or a review that is out of date (409). */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
