package com.builtbyjuls.arat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class CorrelationIdFilterTest {

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(new CorrelationIdFilter())
                .build();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
        logAppender = new ListAppender<>();
        logAppender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CorrelationIdFilter.class))
                .detachAppender(logAppender);
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void propagatesValidInboundIdToResponseRequestAndMdc() throws Exception {
        var correlationId = "request_123-abc.def";

        var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/probe")
                        .header(CorrelationIdFilter.HEADER_NAME, correlationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attribute").value(correlationId))
                .andExpect(jsonPath("$.mdc").value(correlationId))
                .andReturn()
                .getResponse();

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(correlationId);
        assertThat(response.getContentAsString()).contains(correlationId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesIdWhenHeaderIsAbsent() throws Exception {
        var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/probe"))
                .andReturn()
                .getResponse();

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).satisfies(this::assertGeneratedId);
        assertThat(response.getContentAsString()).contains(response.getHeader(CorrelationIdFilter.HEADER_NAME));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unsafe value", "non_ascii_\u00e9"})
    void replacesUnsafeInboundId(String inboundValue) throws Exception {
        var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/probe")
                        .header(CorrelationIdFilter.HEADER_NAME, inboundValue))
                .andReturn()
                .getResponse();

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isNotEqualTo(inboundValue)
                .satisfies(this::assertGeneratedId);
    }

    @Test
    void replacesOverlongInboundId() throws Exception {
        var inboundValue = "a".repeat(65);

        var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/probe")
                        .header(CorrelationIdFilter.HEADER_NAME, inboundValue))
                .andReturn()
                .getResponse();

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isNotEqualTo(inboundValue)
                .satisfies(this::assertGeneratedId);
    }

    @Test
    void clearsMdcAndLogsOnlySafeCompletionFieldsAfterDownstreamException() {
        assertThatThrownBy(() -> mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/group-invites/invitation-token-must-not-log/accept?token=must-not-log")
                        .header("Authorization", "Bearer must-not-log")))
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        assertThat(logAppender.list).hasSize(1);
        var event = logAppender.list.getFirst();
        var correlationId = event.getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY);

        assertGeneratedId(correlationId);
        assertThat(event.getFormattedMessage()).isEqualTo("HTTP request completed");
        assertThat(event.getKeyValuePairs())
                .extracting(pair -> pair.key)
                .containsExactlyInAnyOrder("method", "path", "status", "elapsedMs", "correlationId");
        assertThat(event.getKeyValuePairs())
                .extracting(pair -> pair.value)
                .doesNotContain(
                        "token=must-not-log",
                        "Bearer must-not-log",
                        "invitation-token-must-not-log");
        assertThat(event.getKeyValuePairs())
                .filteredOn(pair -> pair.key.equals("path"))
                .extracting(pair -> pair.value)
                .containsExactly("/group-invites/{token}/accept");
        assertThat(event.getKeyValuePairs())
                .filteredOn(pair -> pair.key.equals("status"))
                .extracting(pair -> pair.value)
                .containsExactly(500);
        assertThat(event.toString()).doesNotContain(
                "token=must-not-log",
                "Bearer must-not-log",
                "invitation-token-must-not-log");
    }

    private void assertGeneratedId(String correlationId) {
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @RestController
    @RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    static class TestController {

        @GetMapping("/probe")
        Map<String, String> probe(jakarta.servlet.http.HttpServletRequest request) {
            return Map.of(
                    "attribute", (String) request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE),
                    "mdc", MDC.get(CorrelationIdFilter.MDC_KEY));
        }

        @GetMapping("/group-invites/{token}/accept")
        void failure() {
            throw new IllegalStateException("expected failure");
        }
    }
}
