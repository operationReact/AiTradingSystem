package in.aitradingsystem.common;

import in.aitradingsystem.risk.RiskApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Consistent API error bodies. Internal exception details are logged, never returned. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return response(HttpStatus.BAD_REQUEST, "Request validation failed", errors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraints(ConstraintViolationException ex, HttpServletRequest request) {
        List<String> errors = ex.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList();
        return response(HttpStatus.BAD_REQUEST, "Request validation failed", errors, request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> badRequest(Exception ex, HttpServletRequest request) {
        String message = ex instanceof MissingRequestHeaderException missing
            ? "Required header is missing: " + missing.getHeaderName()
            : "Malformed request";
        return response(HttpStatus.BAD_REQUEST, message, List.of(), request);
    }

    @ExceptionHandler(RiskApiException.class)
    ResponseEntity<ApiError> risk(RiskApiException ex, HttpServletRequest request) {
        ApiError body = new ApiError(
                Instant.now(), ex.status().value(), ex.code().name(), ex.getMessage(),
                ex.code().name(), ex.getMessage(), request.getRequestURI(),
                MDC.get(CorrelationIdFilter.MDC_KEY), List.of());
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> illegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Invalid request", List.of(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> conflict(IdempotencyConflictException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "Request conflicts with current state", List.of(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "Resource not found", List.of(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> responseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = status.is5xxServerError() ? "Internal server error" : status.getReasonPhrase();
        return response(status, message, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API error", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", List.of(), request);
    }

    private static ResponseEntity<ApiError> response(
            HttpStatus status, String message, List<String> errors, HttpServletRequest request) {
        ApiError body = new ApiError(
            Instant.now(), status.value(), status.getReasonPhrase(), message,
            status.getReasonPhrase(), message,
            request.getRequestURI(), MDC.get(CorrelationIdFilter.MDC_KEY), List.copyOf(errors));
        return ResponseEntity.status(status).body(body);
    }
}
