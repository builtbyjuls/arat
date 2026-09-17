package com.builtbyjuls.arat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

class ApiProblemAdviceTest {

    private static final String CORRELATION_ID = "problem-test-123";

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        var problemFactory = new ApiProblemFactory();
        var responseWriter = new ProblemResponseWriter(new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ApiProblemAdvice(problemFactory, responseWriter))
                .addFilters(new CorrelationIdFilter())
                .build();

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ApiProblemAdvice.class);
        logAppender = new ListAppender<>();
        logAppender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ApiProblemAdvice.class))
                .detachAppender(logAppender);
    }

    @Test
    void mapsMalformedJsonToSafeProblem() throws Exception {
        assertProblem(mockMvc.perform(post("/probe/body")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json")), 400, "MALFORMED_REQUEST", "malformed-request", "/probe/body")
                .andExpect(jsonPath("$.detail").value("The request could not be read."));
    }

    @Test
    void mapsUnsupportedMediaTypeToProblem() throws Exception {
        assertProblem(mockMvc.perform(post("/probe/body")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text")), 415, "UNSUPPORTED_MEDIA_TYPE", "unsupported-media-type", "/probe/body");
    }

    @Test
    void mapsMethodNotAllowedToProblem() throws Exception {
        assertProblem(mockMvc.perform(post("/probe/read")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)), 405, "METHOD_NOT_ALLOWED", "method-not-allowed", "/probe/read")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Allow", "GET"));
    }

    @Test
    void mapsRouteNotFoundToProblem() throws Exception {
        assertProblem(mockMvc.perform(get("/missing?query=must-not-appear")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)), 404, "ROUTE_NOT_FOUND", "route-not-found", "/missing");
    }

    @Test
    void mapsMissingAndMismatchedParametersToSafeMalformedRequestProblems() throws Exception {
        assertProblem(mockMvc.perform(get("/probe/parameter")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)), 400, "MALFORMED_REQUEST", "malformed-request", "/probe/parameter");
        assertProblem(mockMvc.perform(get("/probe/parameter?count=not-a-number")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)), 400, "MALFORMED_REQUEST", "malformed-request", "/probe/parameter");
    }

    @Test
    void mapsIncompatibleAcceptHeaderToProblem() throws Exception {
        assertProblem(mockMvc.perform(get("/probe/read")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .accept(MediaType.TEXT_PLAIN)), 406, "NOT_ACCEPTABLE", "not-acceptable", "/probe/read");
    }

    @Test
    void mapsValidationFailuresToSortedSafeViolations() throws Exception {
        assertProblem(mockMvc.perform(post("/probe/body")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"city\":\"\"}")), 422, "VALIDATION_FAILED", "validation-failed", "/probe/body")
                .andExpect(jsonPath("$.detail").value("One or more fields are invalid."))
                .andExpect(jsonPath("$.violations[0].field").value("city"))
                .andExpect(jsonPath("$.violations[1].field").value("name"))
                .andExpect(jsonPath("$.violations[0].message").value("Invalid value."))
                .andExpect(jsonPath("$.violations[1].message").value("Invalid value."));
    }

    @Test
    void mapsDirectJakartaValidationFailuresToValidationProblem() throws Exception {
        assertProblem(mockMvc.perform(get("/probe/constraint")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)), 422, "VALIDATION_FAILED", "validation-failed", "/probe/constraint");
    }

    @Test
    void retainsNestedFieldsFromMethodValidation() throws Exception {
        assertProblem(mockMvc.perform(post("/probe/method-validation")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"city\":\"\"}")), 422, "VALIDATION_FAILED", "validation-failed", "/probe/method-validation")
                .andExpect(jsonPath("$.violations[0].field").value("city"))
                .andExpect(jsonPath("$.violations[1].field").value("name"));
    }

    @Test
    void mapsInvalidControllerOutputToLoggedInternalError() throws Exception {
        assertProblem(mockMvc.perform(get("/probe/invalid-output")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)), 500, "INTERNAL_ERROR", "internal-error", "/probe/invalid-output");

        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.getFirst().getMDCPropertyMap())
                .containsEntry(CorrelationIdFilter.MDC_KEY, CORRELATION_ID);
    }

    @Test
    void mapsUnexpectedFailuresWithoutLeakingAndLogsDiagnosticCause() throws Exception {
        var response = assertProblem(mockMvc.perform(get("/probe/failure")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)), 500, "INTERNAL_ERROR", "internal-error", "/probe/failure")
                .andExpect(jsonPath("$.detail").value("The server could not complete the request."))
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsString()).doesNotContain("secret implementation message");
        assertThat(logAppender.list).hasSize(1);
        var event = logAppender.list.getFirst();
        assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationIdFilter.MDC_KEY, CORRELATION_ID);
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("secret implementation message");
    }

    private ResultActions assertProblem(
            ResultActions result, int expectedStatus, String code, String type, String instance) throws Exception {
        var actions = result.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://arat.example/problems/" + type))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(jsonPath("$.violations").isArray());
        assertThat(actions.andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER_NAME))
                .isEqualTo(CORRELATION_ID);
        return actions;
    }

    @RestController
    @RequestMapping("/probe")
    static class TestController {

        @GetMapping(value = "/read", produces = MediaType.APPLICATION_JSON_VALUE)
        String read() {
            return "ok";
        }

        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@Valid @RequestBody ValidationProbe body) {
        }

        @PostMapping(value = "/method-validation", consumes = MediaType.APPLICATION_JSON_VALUE)
        void methodValidation(@Valid @NotNull @RequestBody ValidationProbe body) {
        }

        @GetMapping("/constraint")
        void constraint() {
            throw new jakarta.validation.ConstraintViolationException("private detail", java.util.Set.of());
        }

        @GetMapping("/parameter")
        void parameter(@org.springframework.web.bind.annotation.RequestParam int count) {
        }

        @GetMapping("/invalid-output")
        @NotBlank
        String invalidOutput() {
            return "";
        }

        @GetMapping("/failure")
        void failure() {
            throw new IllegalStateException("secret implementation message");
        }
    }

    record ValidationProbe(@NotBlank String name, @Size(min = 3) String city) {
    }
}
