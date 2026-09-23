package com.builtbyjuls.arat.marketplace.api;

import com.builtbyjuls.arat.planning.api.PlanCancellationException;
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

@RestControllerAdvice(assignableTypes = PlanCancellationController.class)
class PlanCancellationProblemAdvice {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    PlanCancellationProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
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

    @ExceptionHandler(PlanCancellationException.class)
    void cancellationFailure(
            PlanCancellationException exception,
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
                    "The plan can be cancelled only while collaborating or open for offers.");
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
