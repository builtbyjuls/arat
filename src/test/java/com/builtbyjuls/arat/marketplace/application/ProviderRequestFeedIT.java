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
import java.util.Base64;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderRequestFeedIT extends PostgreSqlIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareClient() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void pagesExactEligibleRecipientRowsAndRetainsTerminalAndExpiredHistory() throws Exception {
        var fixture = fixture();

        var first = feed(fixture.staffId(), fixture.providerId(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].requestId").value(fixture.openRequestId().toString()))
                .andExpect(jsonPath("$.items[0].actionable").value(true))
                .andReturn();
        assertThat(fieldNames(objectMapper.readTree(first.getResponse().getContentAsByteArray()).path("items").get(0)))
                .containsExactlyInAnyOrder(
                        "requestId", "requestVersion", "publishedAt", "state", "actionable", "category", "timeZone",
                        "area", "requestedWindow", "headcount", "budget", "mustHaves", "categoryAttributes",
                        "providerSafeNotes", "offerDeadline");
        var middleCursor = cursor(first.getResponse().getContentAsByteArray());

        var middle = feed(fixture.staffId(), fixture.providerId(), middleCursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].requestId").value(fixture.expiredRequestId().toString()))
                .andExpect(jsonPath("$.items[0].state").value("OPEN"))
                .andExpect(jsonPath("$.items[0].actionable").value(false))
                .andReturn();
        var finalCursor = cursor(middle.getResponse().getContentAsByteArray());

        feed(fixture.staffId(), fixture.providerId(), finalCursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].requestId").value(fixture.closedRequestId().toString()))
                .andExpect(jsonPath("$.items[0].state").value("CLOSED"))
                .andExpect(jsonPath("$.items[0].actionable").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void rejectsMalformedAndCrossProviderCursorsAndHidesIneligibleRecipients() throws Exception {
        var fixture = fixture();
        var cursor = cursor(feed(fixture.staffId(), fixture.providerId(), null).andReturn().getResponse().getContentAsByteArray());

        feed(fixture.staffId(), fixture.providerId(), "not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        feed(fixture.staffId(), fixture.providerId(), cursorWithCreatedAt(fixture.providerId(), "+300000-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        feed(fixture.staffId(), fixture.providerId(), cursorWithCreatedAt(fixture.providerId(), "2026-01-01T00:00:00.000000001Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        feed(fixture.otherProviderStaffId(), fixture.otherProviderId(), cursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mockMvc.perform(get("/api/v1/providers/{providerId}/request-feed", fixture.providerId())
                        .with(actor(fixture.staffId()))
                        .param("limit", "101")
                        .header(CorrelationIdFilter.HEADER_NAME, "provider-request-feed-test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        feed(fixture.otherProviderStaffId(), fixture.providerId(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
    }

    @Test
    void publishesExecutableOpenApiForProviderRequestFeed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/request-feed'].get.operationId")
                        .value("listProviderRequestFeed"))
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/request-feed'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/request-feed'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/request-feed'].get.responses['404']").exists());
    }

    private org.springframework.test.web.servlet.ResultActions feed(UUID actorId, UUID providerId, String cursor) throws Exception {
        var request = get("/api/v1/providers/{providerId}/request-feed", providerId)
                .with(actor(actorId))
                .param("limit", "1")
                .header(CorrelationIdFilter.HEADER_NAME, "provider-request-feed-test");
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return mockMvc.perform(request);
    }

    private String cursor(byte[] body) throws Exception {
        JsonNode response = objectMapper.readTree(body);
        assertThat(response.path("nextCursor").isTextual()).isTrue();
        return response.path("nextCursor").textValue();
    }

    private String cursorWithCreatedAt(UUID providerId, String createdAt) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(Map.of(
                "version", 1,
                "providerId", providerId,
                "createdAt", createdAt,
                "requestId", UUID.randomUUID())));
    }

    private Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    private RequestPostProcessor actor(UUID accountId) {
        return authentication(new TestingAuthenticationToken(new AuthenticatedActor(accountId, Set.of()), null, "ROLE_USER"));
    }

    private Fixture fixture() {
        var ownerId = UUID.randomUUID();
        var staffId = UUID.randomUUID();
        var otherProviderStaffId = UUID.randomUUID();
        var providerId = UUID.randomUUID();
        var otherProviderId = UUID.randomUUID();
        var createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).truncatedTo(ChronoUnit.MICROS);
        insertAccount(ownerId);
        insertAccount(staffId);
        insertAccount(otherProviderStaffId);
        insertProvider(providerId, "Feed provider");
        insertProvider(otherProviderId, "Other feed provider");
        insertStaff(providerId, staffId);
        insertStaff(otherProviderId, otherProviderStaffId);

        var closedRequestId = insertRequest(ownerId, "CLOSED", createdAt.plusMinutes(1), createdAt.plusMinutes(20));
        var expiredRequestId = insertRequest(ownerId, "OPEN", createdAt.plusMinutes(2), createdAt.plusMinutes(3));
        var openRequestId = insertRequest(ownerId, "OPEN", createdAt.plusMinutes(3), createdAt.plusHours(1));
        insertRecipient(closedRequestId, providerId, 1, "ACTIVE", createdAt.plusMinutes(1));
        insertRecipient(expiredRequestId, providerId, 1, "ACTIVE", createdAt.plusMinutes(2));
        insertRecipient(openRequestId, providerId, 1, "ACTIVE", createdAt.plusMinutes(3));
        insertRecipient(
                insertRequest(ownerId, "CLOSED", createdAt.plusMinutes(2).plusSeconds(30), createdAt.plusHours(1)),
                otherProviderId,
                1,
                "ACTIVE",
                createdAt.plusMinutes(2).plusSeconds(30));
        insertRecipient(insertRequest(ownerId, "CLOSED", createdAt.plusMinutes(4), createdAt.plusHours(1)), providerId, 2, "ACTIVE", createdAt.plusMinutes(4));
        insertRecipient(insertRequest(ownerId, "CLOSED", createdAt.plusMinutes(5), createdAt.plusHours(1)), providerId, 1, "REVOKED", createdAt.plusMinutes(5));
        return new Fixture(staffId, otherProviderStaffId, providerId, otherProviderId,
                openRequestId, expiredRequestId, closedRequestId);
    }

    private void insertAccount(UUID accountId) {
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, 'Feed account')")
                .param("accountId", accountId)
                .update();
    }

    private void insertProvider(UUID providerId, String displayName) {
        jdbcClient.sql("""
                        INSERT INTO provider_organization (
                            provider_id, display_name, status, verification_status, version, eligibility_version
                        ) VALUES (:providerId, :displayName, 'ACTIVE', 'VERIFIED', 1, 1)
                        """)
                .param("providerId", providerId)
                .param("displayName", displayName)
                .update();
    }

    private void insertStaff(UUID providerId, UUID accountId) {
        jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (provider_id, account_id, role, status)
                        VALUES (:providerId, :accountId, 'STAFF', 'ACTIVE')
                        """)
                .param("providerId", providerId)
                .param("accountId", accountId)
                .update();
    }

    private UUID insertRequest(UUID ownerId, String state, OffsetDateTime publishedAt, OffsetDateTime deadline) {
        return new TransactionTemplate(transactionManager).execute(
                status -> insertRequestInTransaction(ownerId, state, publishedAt, deadline));
    }

    private UUID insertRequestInTransaction(UUID ownerId, String state, OffsetDateTime publishedAt, OffsetDateTime deadline) {
        var groupId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        var open = "OPEN".equals(state);
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Feed group', '', 'ACTIVE', :ownerId)
                        """)
                .param("groupId", groupId)
                .param("ownerId", ownerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, current_request_id, created_by_account_id)
                        VALUES (:planId, :groupId, 'Private feed plan', 'COLLABORATING', NULL, :ownerId)
                        """)
                .param("planId", planId)
                .param("groupId", groupId)
                .param("ownerId", ownerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_published_request (
                            request_id, plan_id, request_version, state, distribution_mode,
                            category, time_zone, area_code, radius_km, requested_starts_at,
                            requested_ends_at, minimum_headcount, maximum_headcount, must_haves,
                            category_attributes, offer_deadline, published_by_account_id, published_at, closed_at
                        ) VALUES (
                            :requestId, :planId, 1, :state, 'MATCHED_POOL',
                            'COURT', 'Asia/Manila', 'BGC', 5, :startsAt,
                            :endsAt, 4, 10, ARRAY['parking']::varchar[],
                            '{}'::jsonb, :deadline, :ownerId, :publishedAt, :closedAt
                        )
                        """)
                .param("requestId", requestId)
                .param("planId", planId)
                .param("state", state)
                .param("startsAt", publishedAt.plusDays(2))
                .param("endsAt", publishedAt.plusDays(2).plusHours(2))
                .param("deadline", deadline)
                .param("ownerId", ownerId)
                .param("publishedAt", publishedAt)
                .param("closedAt", open ? null : publishedAt.plusMinutes(1))
                .update();
        if (open) {
            jdbcClient.sql("""
                            UPDATE planning_plan
                            SET state = 'OPEN_FOR_OFFERS', current_request_id = :requestId
                            WHERE plan_id = :planId
                            """)
                    .param("requestId", requestId)
                    .param("planId", planId)
                    .update();
        }
        return requestId;
    }

    private void insertRecipient(UUID requestId, UUID providerId, long eligibilityVersion, String accessState, OffsetDateTime createdAt) {
        jdbcClient.sql("""
                        INSERT INTO marketplace_request_recipient (
                            published_request_id, provider_id, provider_eligibility_version,
                            source, access_state, created_at
                        ) VALUES (:requestId, :providerId, :eligibilityVersion, 'MATCH_RULE', :accessState, :createdAt)
                        """)
                .param("requestId", requestId)
                .param("providerId", providerId)
                .param("eligibilityVersion", eligibilityVersion)
                .param("accessState", accessState)
                .param("createdAt", createdAt)
                .update();
    }

    private record Fixture(
            UUID staffId,
            UUID otherProviderStaffId,
            UUID providerId,
            UUID otherProviderId,
            UUID openRequestId,
            UUID expiredRequestId,
            UUID closedRequestId) {
    }
}
