package com.builtbyjuls.arat;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
    "arat.invitation.token.active-key-id=runtime",
    "arat.invitation.token.keys.runtime=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@ActiveProfiles("prod")
class NonLocalOperationalEndpointsIT extends PostgreSqlIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void doesNotExposePrometheusOutsideAllowedProfiles() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
    }

    @Test
    void keepsSensitiveActuatorPathsUnavailableOutsideAllowedProfiles() throws Exception {
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
