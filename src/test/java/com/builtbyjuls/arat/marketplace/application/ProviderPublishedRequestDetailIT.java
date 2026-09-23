package com.builtbyjuls.arat.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderPublishedRequestDetailIT extends PostgreSqlIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareClient() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void activeAdminAndStaffReadOnlyTheProviderSafeTerminalSnapshot() throws Exception {
        var fixture = fixture();

        var response = readAs(fixture.adminId(), fixture.providerId(), fixture.requestId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(fixture.requestId().toString()))
                .andExpect(jsonPath("$.requestVersion").value(1))
                .andExpect(jsonPath("$.publishedAt").exists())
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.actionable").value(false))
                .andExpect(jsonPath("$.category").value("COURT"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Manila"))
                .andExpect(jsonPath("$.area.code").value("BGC"))
                .andExpect(jsonPath("$.area.radiusKm").value(5))
                .andExpect(jsonPath("$.requestedWindow.startAt").exists())
                .andExpect(jsonPath("$.requestedWindow.endAt").exists())
                .andExpect(jsonPath("$.headcount.minimum").value(4))
                .andExpect(jsonPath("$.headcount.maximum").value(10))
                .andExpect(jsonPath("$.budget.currency").value("PHP"))
                .andExpect(jsonPath("$.budget.minimumAmount").value("1000.00"))
                .andExpect(jsonPath("$.budget.maximumAmount").value("2500.00"))
                .andExpect(jsonPath("$.mustHaves[0]").value("parking"))
                .andExpect(jsonPath("$.categoryAttributes.courtCount").value(2))
                .andExpect(jsonPath("$.providerSafeNotes").value("Indoor court preferred."))
                .andExpect(jsonPath("$.offerDeadline").exists())
                .andExpect(jsonPath("$.groupId").doesNotExist())
                .andExpect(jsonPath("$.groupName").doesNotExist())
                .andExpect(jsonPath("$.planId").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.createdByAccountId").doesNotExist())
                .andExpect(jsonPath("$.members").doesNotExist())
                .andExpect(jsonPath("$.preferences").doesNotExist())
                .andExpect(jsonPath("$.attendance").doesNotExist())
                .andExpect(jsonPath("$.privateNote").doesNotExist())
                .andExpect(jsonPath("$.employer").doesNotExist())
                .andExpect(jsonPath("$.contact").doesNotExist())
                .andReturn().getResponse();

        assertProviderSafeFieldSets(objectMapper.readTree(response.getContentAsByteArray()));

        readAs(fixture.staffId(), fixture.providerId(), fixture.requestId())
                .andExpect(status().isOk());
    }

    @Test
    void hidesWrongProviderNonRecipientRemovedStaffAndUnknownRequest() throws Exception {
        var fixture = fixture();

        expectPrivateNotFound(readAs(fixture.adminId(), fixture.otherProviderId(), fixture.requestId()));
        expectPrivateNotFound(readAs(fixture.staffId(), fixture.otherProviderId(), fixture.requestId()));
        expectPrivateNotFound(readAs(fixture.otherProviderStaffId(), fixture.otherProviderId(), fixture.requestId()));
        expectPrivateNotFound(readAs(fixture.outsiderId(), fixture.providerId(), fixture.requestId()));
        expectPrivateNotFound(readAs(fixture.removedStaffId(), fixture.providerId(), fixture.requestId()));
        expectPrivateNotFound(readAs(fixture.revokedRecipientStaffId(), fixture.revokedRecipientProviderId(), fixture.requestId()));
        expectPrivateNotFound(readAs(fixture.adminId(), fixture.providerId(), UUID.randomUUID()));
    }

    @Test
    void suspensionRestorationAndEligibilityVersionChangesKeepTheOriginalGrantUnreadable() throws Exception {
        var fixture = fixture();

        jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = 'SUSPENDED', eligibility_version = 2
                        WHERE provider_id = :providerId
                        """)
                .param("providerId", fixture.providerId())
                .update();
        expectPrivateNotFound(readAs(fixture.adminId(), fixture.providerId(), fixture.requestId()));

        jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = 'VERIFIED', eligibility_version = 3
                        WHERE provider_id = :providerId
                        """)
                .param("providerId", fixture.providerId())
                .update();
        expectPrivateNotFound(readAs(fixture.adminId(), fixture.providerId(), fixture.requestId()));
    }

    @Test
    void publishesExecutableOpenApiForProviderRequestDetail() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/published-requests/{requestId}'].get.operationId")
                        .value("readProviderPublishedRequest"))
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/published-requests/{requestId}'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/published-requests/{requestId}'].get.responses['404']").exists());
    }

    private ResultActions readAs(UUID actorId, UUID providerId, UUID requestId) throws Exception {
        return mockMvc.perform(get("/api/v1/providers/{providerId}/published-requests/{requestId}", providerId, requestId)
                .with(actor(actorId))
                .header(CorrelationIdFilter.HEADER_NAME, "provider-request-detail-test"));
    }

    private void expectPrivateNotFound(ResultActions response) throws Exception {
        response.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Private resource not found"))
                .andExpect(jsonPath("$.detail").value("The requested private resource was not found."));
    }

    private RequestPostProcessor actor(UUID accountId) {
        return authentication(new TestingAuthenticationToken(new AuthenticatedActor(accountId, Set.of()), null, "ROLE_USER"));
    }

    private void assertProviderSafeFieldSets(JsonNode response) {
        assertThat(fieldNames(response)).containsExactlyInAnyOrder(
                "requestId", "requestVersion", "publishedAt", "state", "actionable", "category", "timeZone",
                "area", "requestedWindow", "headcount", "budget", "mustHaves", "categoryAttributes",
                "providerSafeNotes", "offerDeadline");
        assertThat(fieldNames(response.path("area"))).containsExactlyInAnyOrder("code", "radiusKm");
        assertThat(fieldNames(response.path("requestedWindow"))).containsExactlyInAnyOrder("startAt", "endAt");
        assertThat(fieldNames(response.path("headcount"))).containsExactlyInAnyOrder("minimum", "maximum");
        assertThat(fieldNames(response.path("budget"))).containsExactlyInAnyOrder(
                "currency", "minimumAmount", "maximumAmount");
        assertThat(fieldNames(response.path("categoryAttributes"))).containsExactly("courtCount");
    }

    private Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    private Fixture fixture() {
        var ownerId = UUID.randomUUID();
        var adminId = UUID.randomUUID();
        var staffId = UUID.randomUUID();
        var removedStaffId = UUID.randomUUID();
        var outsiderId = UUID.randomUUID();
        var otherProviderStaffId = UUID.randomUUID();
        var revokedRecipientStaffId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        var providerId = UUID.randomUUID();
        var otherProviderId = UUID.randomUUID();
        var revokedRecipientProviderId = UUID.randomUUID();
        var publishedAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3).truncatedTo(ChronoUnit.MICROS);

        insertAccounts(ownerId, adminId, staffId, removedStaffId, outsiderId, otherProviderStaffId, revokedRecipientStaffId);
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Provider request group', '', 'ACTIVE', :ownerId)
                        """)
                .param("groupId", groupId)
                .param("ownerId", ownerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id)
                        VALUES (:planId, :groupId, 'Private plan title', 'COLLABORATING', :ownerId)
                        """)
                .param("planId", planId)
                .param("groupId", groupId)
                .param("ownerId", ownerId)
                .update();
        insertProvider(providerId, "Eligible provider");
        insertProvider(otherProviderId, "Other provider");
        insertProvider(revokedRecipientProviderId, "Revoked recipient provider");
        insertMembership(providerId, adminId, "ADMIN", "ACTIVE");
        insertMembership(providerId, staffId, "STAFF", "ACTIVE");
        insertMembership(providerId, removedStaffId, "STAFF", "REMOVED");
        insertMembership(otherProviderId, otherProviderStaffId, "STAFF", "ACTIVE");
        insertMembership(revokedRecipientProviderId, revokedRecipientStaffId, "STAFF", "ACTIVE");
        jdbcClient.sql("""
                        INSERT INTO planning_published_request (
                            request_id, plan_id, request_version, state, distribution_mode,
                            category, time_zone, area_code, radius_km, requested_starts_at,
                            requested_ends_at, minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                            provider_safe_notes, category_attributes, offer_deadline,
                            published_by_account_id, published_at, closed_at
                        )
                        VALUES (
                            :requestId, :planId, 1, 'CLOSED', 'MATCHED_POOL',
                            'COURT', 'Asia/Manila', 'BGC', 5, :startsAt,
                            :endsAt, 4, 10, 'PHP', 100000, 250000, ARRAY['parking']::varchar[],
                            'Indoor court preferred.', '{"courtCount":2}'::jsonb, :offerDeadline,
                            :ownerId, :publishedAt, :closedAt
                        )
                        """)
                .param("requestId", requestId)
                .param("planId", planId)
                .param("startsAt", publishedAt.plusDays(3))
                .param("endsAt", publishedAt.plusDays(3).plusHours(2))
                .param("offerDeadline", publishedAt.plusDays(2))
                .param("ownerId", ownerId)
                .param("publishedAt", publishedAt)
                .param("closedAt", publishedAt.plusHours(1))
                .update();
        jdbcClient.sql("""
                        INSERT INTO marketplace_request_recipient (
                            published_request_id, provider_id, provider_eligibility_version,
                            source, access_state
                        )
                        VALUES (:requestId, :providerId, 1, 'MATCH_RULE', 'ACTIVE')
                        """)
                .param("requestId", requestId)
                .param("providerId", providerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO marketplace_request_recipient (
                            published_request_id, provider_id, provider_eligibility_version,
                            source, access_state
                        )
                        VALUES (:requestId, :providerId, 1, 'MATCH_RULE', 'REVOKED')
                        """)
                .param("requestId", requestId)
                .param("providerId", revokedRecipientProviderId)
                .update();
        return new Fixture(adminId, staffId, removedStaffId, outsiderId, otherProviderStaffId,
                revokedRecipientStaffId, providerId, otherProviderId, revokedRecipientProviderId, requestId);
    }

    private void insertAccounts(UUID... accountIds) {
        for (var accountId : accountIds) {
            jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, 'Test account')")
                    .param("accountId", accountId)
                    .update();
        }
    }

    private void insertProvider(UUID providerId, String displayName) {
        jdbcClient.sql("""
                        INSERT INTO provider_organization (
                            provider_id, display_name, status, verification_status, version, eligibility_version
                        )
                        VALUES (:providerId, :displayName, 'ACTIVE', 'VERIFIED', 1, 1)
                        """)
                .param("providerId", providerId)
                .param("displayName", displayName)
                .update();
    }

    private void insertMembership(UUID providerId, UUID accountId, String role, String status) {
        jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (provider_id, account_id, role, status, removed_at)
                        VALUES (:providerId, :accountId, :role, :status,
                                CASE WHEN :status = 'REMOVED' THEN statement_timestamp() ELSE NULL END)
                        """)
                .param("providerId", providerId)
                .param("accountId", accountId)
                .param("role", role)
                .param("status", status)
                .update();
    }

    private record Fixture(
            UUID adminId,
            UUID staffId,
            UUID removedStaffId,
            UUID outsiderId,
            UUID otherProviderStaffId,
            UUID revokedRecipientStaffId,
            UUID providerId,
            UUID otherProviderId,
            UUID revokedRecipientProviderId,
            UUID requestId) {
    }
}
