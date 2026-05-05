package com.agripulse.app.exception;

import com.agripulse.app.dto.ApiErrorResponse;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

// @ControllerAdvice lets us centralize error handling for every controller.
// This keeps controllers clean and gives the API one consistent error format.
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .toList();

        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed for the request body.", details);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException exception) {
        log.warn("Bad request handled: {}", exception.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), List.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleServiceFailure(IllegalStateException exception) {
        log.warn("Service failure handled: {}", exception.getMessage(), exception);
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDatabaseFailure(DataAccessException exception) {
        log.error("Database failure handled: {}", exception.getMessage(), exception);
        return buildResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The database is temporarily unavailable. Please try again shortly.",
                List.of(exception.getClass().getSimpleName())
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedFailure(Exception exception) {
        log.error("Unexpected request failure handled: {}", exception.getMessage(), exception);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "The risk engine could not complete the request right now.",
                List.of(exception.getClass().getSimpleName())
        );
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(HttpStatus status, String message, List<String> details) {
        ApiErrorResponse response = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                details
        );

        return ResponseEntity.status(status).body(response);
    }
}
