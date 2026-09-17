package com.builtbyjuls.arat.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    public ProblemResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.rebuild()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
    }

    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        write(response, problem, HttpHeaders.EMPTY);
    }

    public void write(HttpServletResponse response, ProblemDetail problem, HttpHeaders headers)
            throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        headers.forEach((name, values) -> response.setHeader(name, String.join(", ", values)));
        response.setHeader(
                CorrelationIdFilter.HEADER_NAME,
                (String) problem.getProperties().get("correlationId"));
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
