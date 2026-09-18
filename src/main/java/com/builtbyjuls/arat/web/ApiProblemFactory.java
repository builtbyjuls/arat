package com.builtbyjuls.arat.web;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;

@Component
public class ApiProblemFactory {

    private static final String PROBLEM_TYPE_BASE = "https://arat.example/problems/";
    private static final String UNKNOWN_CORRELATION_ID = "unknown";
    private static final String INVITATION_ACCEPTANCE_PREFIX = "/api/v1/group-invites/";
    private static final String INVITATION_ACCEPTANCE_INSTANCE = "/api/v1/group-invites/accept";

    public ProblemDetail create(
            HttpServletRequest request,
            HttpStatusCode status,
            String code,
            String title,
            String detail,
            List<ProblemViolation> violations) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setInstance(URI.create(instancePath(request.getRequestURI())));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId(request));
        problem.setProperty("violations", violations.stream()
                .sorted(Comparator.comparing(ProblemViolation::field).thenComparing(ProblemViolation::message))
                .toList());
        return problem;
    }

    private String instancePath(String requestUri) {
        if (requestUri.startsWith(INVITATION_ACCEPTANCE_PREFIX) && requestUri.endsWith("/accept")) {
            return INVITATION_ACCEPTANCE_INSTANCE;
        }
        return requestUri;
    }

    private String correlationId(HttpServletRequest request) {
        var value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String correlationId ? correlationId : UNKNOWN_CORRELATION_ID;
    }
}
