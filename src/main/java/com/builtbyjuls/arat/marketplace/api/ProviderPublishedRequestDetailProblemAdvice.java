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

@RestControllerAdvice(assignableTypes = ProviderPublishedRequestDetailController.class)
class ProviderPublishedRequestDetailProblemAdvice {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    ProviderPublishedRequestDetailProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(ProviderPublishedRequestDetailException.class)
    void requestNotFound(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        responseWriter.write(response, problemFactory.create(
                request,
                HttpStatus.NOT_FOUND,
                "PRIVATE_RESOURCE_NOT_FOUND",
                "Private resource not found",
                "The requested private resource was not found.",
                List.of()));
    }
}
