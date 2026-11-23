package com.bnpp.export.exception;

import org.springframework.http.HttpStatus;

/** The signed-in user may not do this (403). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
