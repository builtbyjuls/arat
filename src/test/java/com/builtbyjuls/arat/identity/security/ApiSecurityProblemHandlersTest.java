package com.builtbyjuls.arat.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.ObjectMapper;

class ApiSecurityProblemHandlersTest {

    private static final String CORRELATION_ID = "security-handler-test-123";

    @ParameterizedTest
    @MethodSource("problemHandlers")
    void writesCorrelatedSafeProblem(
            ProblemHandler problemHandler, int expectedStatus, String expectedCode, String expectedChallenge)
            throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/probe");
        request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, CORRELATION_ID);
        var response = new MockHttpServletResponse();

        problemHandler.write(request, response);

        var body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(response.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(CORRELATION_ID);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(expectedChallenge);
        assertThat(body.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("code").asText()).isEqualTo(expectedCode);
        assertThat(body.path("correlationId").asText()).isEqualTo(CORRELATION_ID);
        assertThat(body.path("violations").isArray()).isTrue();
        assertThat(response.getContentAsString()).doesNotContain("private security detail");
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> problemHandlers() {
        var problemFactory = new ApiProblemFactory();
        var responseWriter = new ProblemResponseWriter(new ObjectMapper());
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        (ProblemHandler) (request, response) -> new ApiAuthenticationEntryPoint(problemFactory, responseWriter)
                                .commence(request, response, new BadCredentialsException("private security detail")),
                        401,
                        "AUTHENTICATION_REQUIRED",
                        "Bearer"),
                org.junit.jupiter.params.provider.Arguments.of(
                        (ProblemHandler) (request, response) -> new ApiAccessDeniedHandler(problemFactory, responseWriter)
                                .handle(request, response, new AccessDeniedException("private security detail")),
                        403,
                        "ACCESS_DENIED",
                        null));
    }

    @FunctionalInterface
    private interface ProblemHandler {

        void write(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception;
    }
}
