package com.roundup.exception;

public class SelectionConflictException extends RuntimeException {

    public SelectionConflictException(String message) {
        super(message);
    }
}
