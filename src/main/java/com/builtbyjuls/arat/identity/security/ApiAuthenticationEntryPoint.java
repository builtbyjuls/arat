package com.builtbyjuls.arat.identity.security;

import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import java.io.IOException;
import java.util.List;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    ApiAuthenticationEntryPoint(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException) throws IOException, ServletException {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        responseWriter.write(
                response,
                problemFactory.create(
                        request,
                        HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED",
                        "Authentication required",
                        "Authentication is required to access this resource.",
                        List.of()),
                headers);
    }
}
