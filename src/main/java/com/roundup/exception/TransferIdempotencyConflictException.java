package com.roundup.exception;

public class TransferIdempotencyConflictException extends RuntimeException {

    public TransferIdempotencyConflictException(String message) {
        super(message);
    }
}
