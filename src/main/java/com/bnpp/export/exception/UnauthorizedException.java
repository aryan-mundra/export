package com.bnpp.export.exception;

import org.springframework.http.HttpStatus;

/** No user is signed in, or the sign-in failed (401). */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
