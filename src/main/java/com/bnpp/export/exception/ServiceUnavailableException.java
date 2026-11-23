package com.bnpp.export.exception;

import org.springframework.http.HttpStatus;

/** A database or service the request needs cannot be reached (503). */
public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
