package com.builtbyjuls.arat.marketplace.api;

import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProviderRequestFeedController.class)
class ProviderRequestFeedProblemAdvice {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    ProviderRequestFeedProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(ProviderRequestFeedException.class)
    void feedFailure(
            ProviderRequestFeedException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (exception.reason() == ProviderRequestFeedException.Reason.INVALID_CURSOR) {
            write(request, response, HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Invalid cursor", "The cursor is invalid.");
            return;
        }
        write(request, response, HttpStatus.NOT_FOUND, "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found",
                "The requested private resource was not found.");
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
