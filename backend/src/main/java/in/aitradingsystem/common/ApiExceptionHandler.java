package in.aitradingsystem.common;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Consistent RFC 7807 error bodies. Internal details are logged, never returned. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraints(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed",
            ex.getConstraintViolations().stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList());
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    ProblemDetail badRequest(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, ex instanceof MissingRequestHeaderException
            ? "Required header is missing: " + ((MissingRequestHeaderException) ex).getHeaderName()
            : "Malformed request", List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), List.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail conflict(IdempotencyConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), List.of());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> responseStatus(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatusCode(),
            ex.getReason() == null ? "Request rejected" : ex.getReason());
        pd.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(ex.getStatusCode()).body(pd);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled API error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", List.of());
    }

    private static ProblemDetail problem(HttpStatus status, String detail, List<String> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        if (!errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return pd;
    }
}