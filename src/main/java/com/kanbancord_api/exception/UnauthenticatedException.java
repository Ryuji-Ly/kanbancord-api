package com.kanbancord_api.exception;

public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException() {
        super("Authentication required");
    }

    public UnauthenticatedException(String message) {
        super(message);
    }
}
