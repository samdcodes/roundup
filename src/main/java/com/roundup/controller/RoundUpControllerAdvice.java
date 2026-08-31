package com.roundup.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.roundup.exception.SelectionConflictException;
import com.roundup.exception.TransferIdempotencyConflictException;
import com.roundup.model.api.ApiError;
@RestControllerAdvice
public class RoundUpControllerAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoundUpControllerAdvice.class);

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is invalid");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(SelectionConflictException.class)
    ResponseEntity<ApiError> conflict(SelectionConflictException exception) {
        return error(HttpStatus.CONFLICT, "SELECTION_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(TransferIdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(TransferIdempotencyConflictException exception) {
        return error(
                HttpStatus.CONFLICT, "TRANSFER_IDEMPOTENCY_MISMATCH", exception.getMessage());
    }

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<ApiError> starlingResponseFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        if (status.value() == 429 || status.is5xxServerError()) {
            return error(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "STARLING_UNAVAILABLE",
                    "Starling is temporarily unavailable");
        }
        if (status.value() == HttpStatus.UNAUTHORIZED.value()
                || status.value() == HttpStatus.FORBIDDEN.value()) {
            // The service's own access token is missing, expired, or lacks the required scope.
            // This is a server-side configuration failure, not something the caller can correct.
            return error(
                    HttpStatus.BAD_GATEWAY,
                    "STARLING_AUTH_FAILED",
                    "Starling rejected the service credentials");
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            // A resource the integration expected to exist was not found upstream.
            return error(
                    HttpStatus.BAD_GATEWAY,
                    "STARLING_BAD_RESPONSE",
                    "Starling returned an invalid response");
        }
        if (status.is4xxClientError()) {
            return error(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "STARLING_REJECTED",
                    "Starling rejected the request");
        }
        return error(
                HttpStatus.BAD_GATEWAY,
                "STARLING_BAD_RESPONSE",
                "Starling returned an invalid response");
    }

    @ExceptionHandler(ResourceAccessException.class)
    ResponseEntity<ApiError> starlingUnavailable(ResourceAccessException exception) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "STARLING_UNAVAILABLE",
                "Starling is temporarily unavailable");
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<ApiError> starlingBadResponse(RestClientException exception) {
        return error(
                HttpStatus.BAD_GATEWAY,
                "STARLING_BAD_RESPONSE",
                "Starling returned an invalid response");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpectedFailure(Exception exception) {
        LOGGER.error("Unexpected request failure");
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }

}
