package com.builtbyjuls.arat.identity.security;

import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import java.io.IOException;
import java.util.List;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
final class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    ApiAccessDeniedHandler(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {
        responseWriter.write(response, problemFactory.create(
                request,
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "Access denied",
                "You are not authorized to access this resource.",
                List.of()));
    }
}
