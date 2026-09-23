package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {PlanQueryController.class, PublishedRequestQueryController.class})
class PlanQueryProblemAdvice {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    PlanQueryProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(PlanQueryException.class)
    void queryFailure(PlanQueryException exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (exception.reason() == PlanQueryException.Reason.INVALID_CURSOR) {
            responseWriter.write(response, problemFactory.create(request, HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Invalid cursor", "The cursor is invalid.", List.of()));
            return;
        }
        responseWriter.write(response, problemFactory.create(request, HttpStatus.NOT_FOUND, "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found", "The requested private resource was not found.", List.of()));
    }
}
