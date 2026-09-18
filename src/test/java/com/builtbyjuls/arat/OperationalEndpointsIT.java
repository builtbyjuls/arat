package com.builtbyjuls.arat;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OperationalEndpointsIT extends PostgreSqlIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @LocalServerPort
    private int port;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void reportsHealthyLivenessAndReadinessWithPostgreSql() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf(
                        "application/vnd.spring-boot.actuator.v3+json")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UP")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc"))));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf(
                        "application/vnd.spring-boot.actuator.v3+json")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UP")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
    }

    @Test
    void exposesPrometheusMetricsAfterAnHttpRequest() throws Exception {
        var restClient = org.springframework.web.client.RestClient.create();
        var liveness = restClient.get()
                .uri("http://localhost:" + port + "/actuator/health/liveness")
                .retrieve()
                .toEntity(String.class);
        org.assertj.core.api.Assertions.assertThat(liveness.getStatusCode().value()).isEqualTo(200);

        var metrics = restClient.get()
                .uri("http://localhost:" + port + "/actuator/prometheus")
                .retrieve()
                .toEntity(String.class);
        org.assertj.core.api.Assertions.assertThat(metrics.getStatusCode().value()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(metrics.getBody())
                .contains("jvm_")
                .contains("http_server_requests");
    }

    @Test
    void keepsSensitiveActuatorPathsUnavailable() throws Exception {
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

}
