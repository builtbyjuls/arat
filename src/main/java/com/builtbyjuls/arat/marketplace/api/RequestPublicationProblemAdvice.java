package com.builtbyjuls.arat.marketplace.api;

import com.builtbyjuls.arat.planning.api.PlanVersionPreconditionException;
import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PublishedRequestController.class)
class RequestPublicationProblemAdvice {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    RequestPublicationProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(PlanVersionPreconditionException.class)
    void preconditionFailure(
            PlanVersionPreconditionException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case PRECONDITION_REQUIRED -> write(request, response, HttpStatus.PRECONDITION_REQUIRED,
                    "PRECONDITION_REQUIRED", "Precondition required", "The If-Match header is required.");
            case INVALID_PRECONDITION -> write(request, response, HttpStatus.BAD_REQUEST,
                    "INVALID_PRECONDITION", "Invalid precondition",
                    "The If-Match header must be a quoted positive integer.");
        }
    }

    @ExceptionHandler(RequestPublicationException.class)
    void publicationFailure(
            RequestPublicationException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case PRIVATE_RESOURCE_NOT_FOUND -> write(request, response, HttpStatus.NOT_FOUND,
                    "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found",
                    "The requested private resource was not found.");
            case FORBIDDEN_ROLE -> write(request, response, HttpStatus.FORBIDDEN,
                    "FORBIDDEN_ROLE", "Forbidden role", "An organizer role is required.");
            case PRECONDITION_FAILED -> write(request, response, HttpStatus.PRECONDITION_FAILED,
                    "PRECONDITION_FAILED", "Precondition failed",
                    "The plan version no longer matches If-Match.");
            case INVALID_PLAN_STATE -> write(request, response, HttpStatus.CONFLICT,
                    "INVALID_PLAN_STATE", "Invalid plan state",
                    "The plan cannot publish a request in its current state.");
            case FINALIZATION_VERSION_CHANGED -> write(request, response, HttpStatus.CONFLICT,
                    "FINALIZATION_VERSION_CHANGED", "Finalization version changed",
                    "The finalization no longer matches the current plan version.");
            case NO_ELIGIBLE_PROVIDERS -> write(request, response, HttpStatus.CONFLICT,
                    "NO_ELIGIBLE_PROVIDERS", "No eligible providers",
                    "No eligible providers match the finalized request.");
            case RECIPIENT_LIMIT_EXCEEDED -> write(request, response, HttpStatus.CONFLICT,
                    "RECIPIENT_LIMIT_EXCEEDED", "Recipient limit exceeded",
                    "The matching audience exceeds the configured recipient limit.");
            case REQUEST_DEADLINE_EXPIRED -> write(request, response, HttpStatus.CONFLICT,
                    "REQUEST_DEADLINE_EXPIRED", "Request deadline expired",
                    "The finalized request deadline has elapsed.");
        }
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String title,
            String detail) throws IOException {
        responseWriter.write(response, problemFactory.create(request, status, code, title, detail, List.of()));
    }
}
