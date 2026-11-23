package com.bnpp.export.exception;

import org.springframework.http.HttpStatus;

/** What the request names does not exist (404). */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
