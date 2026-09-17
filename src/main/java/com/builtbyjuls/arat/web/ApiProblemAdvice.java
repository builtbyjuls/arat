package com.builtbyjuls.arat.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiProblemAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiProblemAdvice.class);
    private static final String INVALID_VALUE = "Invalid value.";

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    public ApiProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    void malformedRequest(
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request", "The request could not be read.", List.of());
    }

    @ExceptionHandler({MissingRequestValueException.class, MethodArgumentTypeMismatchException.class})
    void malformedParameter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request", "The request could not be read.", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    void unsupportedMediaType(
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type", "The request media type is not supported.", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    void notAcceptable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "Not acceptable", "The requested response media type is not available.", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    void methodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", "The request method is not allowed for this resource.", List.of(), exception.getHeaders());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    void routeNotFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Route not found", "The requested route was not found.", List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    void validationFailed(
            MethodArgumentNotValidException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        var violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ProblemViolation(error.getField(), INVALID_VALUE))
                .toList();
        writeValidationFailure(request, response, violations);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    void validationFailed(
            HandlerMethodValidationException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (exception.isForReturnValue()) {
            internalError(exception, request, response);
            return;
        }
        var violations = exception.getParameterValidationResults().stream()
                .flatMap(result -> {
                    if (result instanceof ParameterErrors errors) {
                        return errors.getFieldErrors().stream()
                                .map(error -> new ProblemViolation(error.getField(), INVALID_VALUE));
                    }
                    return Stream.of(new ProblemViolation(
                            fieldName(result.getMethodParameter().getParameterName()), INVALID_VALUE));
                })
                .toList();
        writeValidationFailure(request, response, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    void validationFailed(
            ConstraintViolationException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        var violations = exception.getConstraintViolations().stream()
                .map(violation -> new ProblemViolation(constraintFieldName(violation), INVALID_VALUE))
                .toList();
        writeValidationFailure(request, response, violations);
    }

    @ExceptionHandler(Throwable.class)
    void internalError(
            Throwable exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        LOGGER.atError()
                .addKeyValue(CorrelationIdFilter.MDC_KEY, request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE))
                .setCause(exception)
                .log("Unhandled HTTP request failure");
        write(request, response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error", "The server could not complete the request.", List.of());
    }

    private void writeValidationFailure(
            HttpServletRequest request, HttpServletResponse response, List<ProblemViolation> violations)
            throws IOException {
        write(request, response, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Validation failed", "One or more fields are invalid.", violations);
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String title,
            String detail,
            List<ProblemViolation> violations) throws IOException {
        responseWriter.write(response, problemFactory.create(request, status, code, title, detail, violations));
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String title,
            String detail,
            List<ProblemViolation> violations,
            HttpHeaders headers) throws IOException {
        responseWriter.write(
                response, problemFactory.create(request, status, code, title, detail, violations), headers);
    }

    private String fieldName(String field) {
        return field == null || field.isBlank() ? "request" : field;
    }

    private String constraintFieldName(ConstraintViolation<?> violation) {
        var field = "request";
        for (var node : violation.getPropertyPath()) {
            if (node.getName() != null) {
                field = node.getName();
            }
        }
        return field;
    }
}
