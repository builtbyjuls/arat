package com.builtbyjuls.arat.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("local")
class LocalAuthenticationIT extends PostgreSqlIntegrationTest {

    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String TOKEN = "arat-local-owner-token";
    private static final String CORRELATION_ID = "local-auth-test-123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .detachAppender(logAppender);
    }

    @Test
    void acceptsConfiguredLocalBearerTokenForWhoami() throws Exception {
        mockMvc.perform(get("/api/v1/dev/whoami")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorId").value(ACTOR_ID))
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.platformRoles").doesNotExist())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));

        assertThat(logAppender.list).allSatisfy(event ->
                assertThat(event.toString()).doesNotContain(TOKEN).doesNotContain(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void acceptsCaseInsensitiveBearerScheme() throws Exception {
        mockMvc.perform(get("/api/v1/dev/whoami")
                        .header(HttpHeaders.AUTHORIZATION, "bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorId").value(ACTOR_ID));
    }

    @Test
    void exposesPrometheusWithoutAuthenticationInLocalProfile() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_")));
    }

    @Test
    void keepsSensitiveActuatorPathsUnavailableInLocalProfile() throws Exception {
        for (var path : new String[] {
            "/actuator/env",
            "/actuator/beans",
            "/actuator/configprops",
            "/actuator/heapdump",
            "/actuator/loggers",
            "/actuator/mappings",
            "/actuator/shutdown"
        }) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer", "Bearer ", "Bearer unknown-token", "Bearer malformed token"})
    void rejectsMalformedAndUnknownBearerTokens(String authorization) throws Exception {
        assertAuthenticationProblem(authorization);
    }

    @Test
    void rejectsMissingBearerToken() throws Exception {
        assertAuthenticationProblem(null);
    }

    @Test
    void doesNotShareSecurityContextBetweenRequests() throws Exception {
        mockMvc.perform(get("/api/v1/dev/whoami")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk());

        assertAuthenticationProblem(null);
    }

    private void assertAuthenticationProblem(String authorization) throws Exception {
        var request = get("/api/v1/dev/whoami")
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.type").value("https://arat.example/problems/authentication-required"))
                .andExpect(jsonPath("$.title").value("Authentication required"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Authentication is required to access this resource."))
                .andExpect(jsonPath("$.instance").value("/api/v1/dev/whoami"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(jsonPath("$.violations").isArray());
    }
}
