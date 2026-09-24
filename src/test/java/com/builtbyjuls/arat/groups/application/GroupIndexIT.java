package com.builtbyjuls.arat.groups.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.identity.testing.AratAccountFixture;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GroupIndexIT extends PostgreSqlIntegrationTest {

    private static final UUID OWNER_ID = AratAccountFixture.OWNER.account().accountId();
    private static final UUID MEMBER_ID = AratAccountFixture.MEMBER.account().accountId();
    private static final UUID OUTSIDER_ID = AratAccountFixture.OUTSIDER.account().accountId();
    private static final UUID PROVIDER_ID = AratAccountFixture.PROVIDER.account().accountId();
    private static final String CORRELATION_ID = "group-index-test-123";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account").update();
        for (var account : AratAccountFixture.values()) {
            jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                    .param("accountId", account.account().accountId())
                    .param("displayName", account.account().displayName())
                    .update();
        }
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void listsOnlyActiveMembershipsWithTheCurrentActorsRoleAndNoPrivateFields() throws Exception {
        var sharedGroupId = group(1, "2026-09-20T02:00:00Z", "Shared group");
        var ownerGroupId = group(2, "2026-09-20T01:00:00Z", "Owner group");
        var formerGroupId = group(3, "2026-09-20T03:00:00Z", "Former group");
        var outsiderGroupId = group(4, "2026-09-20T04:00:00Z", "Outsider group");
        membership(sharedGroupId, OWNER_ID, "ORGANIZER", "ACTIVE");
        membership(sharedGroupId, MEMBER_ID, "MEMBER", "ACTIVE");
        membership(ownerGroupId, OWNER_ID, "MEMBER", "ACTIVE");
        membership(formerGroupId, OWNER_ID, "MEMBER", "LEFT");
        membership(outsiderGroupId, OUTSIDER_ID, "ORGANIZER", "ACTIVE");

        var ownerPage = page(OWNER_ID, null, null);
        assertThat(ownerPage.at("/items").size()).isEqualTo(2);
        assertThat(ownerPage.at("/items/0/groupId").asString()).isEqualTo(sharedGroupId.toString());
        assertThat(ownerPage.at("/items/0/role").asString()).isEqualTo("ORGANIZER");
        assertThat(ownerPage.at("/items/1/groupId").asString()).isEqualTo(ownerGroupId.toString());
        assertThat(ownerPage.at("/items/1/role").asString()).isEqualTo("MEMBER");
        assertThat(ownerPage.at("/items/0/createdAt").isMissingNode()).isTrue();
        assertThat(ownerPage.at("/items/0/createdByAccountId").isMissingNode()).isTrue();
        assertThat(ownerPage.at("/items/0/members").isMissingNode()).isTrue();
        assertThat(ownerPage.at("/nextCursor").isNull()).isTrue();

        var memberPage = page(MEMBER_ID, null, null);
        assertThat(memberPage.at("/items").size()).isEqualTo(1);
        assertThat(memberPage.at("/items/0/groupId").asString()).isEqualTo(sharedGroupId.toString());
        assertThat(memberPage.at("/items/0/role").asString()).isEqualTo("MEMBER");

        mockMvc.perform(get("/api/v1/groups")
                        .with(authentication(authenticationFor(PROVIDER_ID)))
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void pagesWithEqualTimestampsWithoutDuplicatesWhenGroupDetailsChange() throws Exception {
        var oldest = group(1, "2026-09-20T00:00:00Z", "Oldest");
        var middle = group(2, "2026-09-20T01:00:00Z", "Middle");
        var newest = group(3, "2026-09-20T01:00:00Z", "Newest");
        membership(oldest, OWNER_ID, "ORGANIZER", "ACTIVE");
        membership(middle, OWNER_ID, "MEMBER", "ACTIVE");
        membership(newest, OWNER_ID, "MEMBER", "ACTIVE");

        var first = page(OWNER_ID, null, 1);
        assertThat(first.at("/items/0/groupId").asString()).isEqualTo(newest.toString());
        jdbcClient.sql("UPDATE group_account SET name = 'Updated newest', version = version + 1 WHERE group_id = :groupId")
                .param("groupId", newest)
                .update();
        var second = page(OWNER_ID, first.at("/nextCursor").asString(), 1);
        assertThat(second.at("/items/0/groupId").asString()).isEqualTo(middle.toString());
        var finalPage = page(OWNER_ID, second.at("/nextCursor").asString(), 1);
        assertThat(finalPage.at("/items/0/groupId").asString()).isEqualTo(oldest.toString());
        assertThat(finalPage.at("/nextCursor").isNull()).isTrue();
    }

    @Test
    void enforcesDefaultAndMaximumLimitsAndRejectsMalformedOrForeignActorCursors() throws Exception {
        for (var suffix = 1; suffix <= 101; suffix++) {
            var groupId = group(suffix, "2026-09-20T00:00:00Z", "Group " + suffix);
            membership(groupId, OWNER_ID, "MEMBER", "ACTIVE");
        }

        var defaultPage = page(OWNER_ID, null, null);
        assertThat(defaultPage.at("/items").size()).isEqualTo(20);
        assertThat(defaultPage.at("/nextCursor").isTextual()).isTrue();
        assertThat(page(OWNER_ID, null, 100).at("/items").size()).isEqualTo(100);

        for (var query : new String[] {
                "?limit=0",
                "?limit=101",
                "?cursor=bad=cursor",
                "?cursor=" + encoded("null"),
                "?cursor=" + encoded("{\"version\":1,\"actorId\":\"" + OWNER_ID + "\",\"createdAt\":\"not-a-timestamp\",\"groupId\":\"" + UUID.randomUUID() + "\"}"),
                "?cursor=" + encoded("{\"version\":2,\"actorId\":\"" + OWNER_ID + "\",\"createdAt\":\"2026-09-20T00:00:00Z\",\"groupId\":\"" + UUID.randomUUID() + "\"}")}) {
            mockMvc.perform(get("/api/v1/groups" + query)
                            .with(authentication(authenticationFor(OWNER_ID)))
                            .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }

        mockMvc.perform(get("/api/v1/groups")
                        .param("cursor", defaultPage.at("/nextCursor").asString())
                        .with(authentication(authenticationFor(MEMBER_ID)))
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void publishesTheGroupIndexInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/groups'].get.operationId").value("listGroups"))
                .andExpect(jsonPath("$.paths['/api/v1/groups'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/GroupPageRepresentation"))
                .andExpect(jsonPath("$.paths['/api/v1/groups'].get.responses['400'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"));
    }

    @Test
    void groupIndexQueryUsesTheActiveActorMembershipIndex() {
        var groupId = group(1, "2026-09-20T00:00:00Z", "Indexed group");
        membership(groupId, OWNER_ID, "ORGANIZER", "ACTIVE");

        List<String> plan = transactionTemplate.execute(status -> {
            jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
            return jdbcClient.sql("""
                            EXPLAIN (COSTS OFF)
                            SELECT g.group_id, g.name, g.description, g.status, g.created_by_account_id,
                                   g.version, g.created_at, g.updated_at, m.role
                            FROM group_membership m
                            JOIN group_account g ON g.group_id = m.group_id
                            WHERE m.account_id = :accountId
                              AND m.status = 'ACTIVE'
                            ORDER BY g.created_at DESC, g.group_id DESC
                            LIMIT 2
                            """)
                    .param("accountId", OWNER_ID)
                    .query(String.class)
                    .list();
        });

        assertThat(plan).anyMatch(line -> line.contains("group_membership_active_actor_listing_idx"));
    }

    private JsonNode page(UUID accountId, String cursor, Integer limit) throws Exception {
        var request = get("/api/v1/groups");
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        return objectMapper.readTree(mockMvc.perform(request
                        .with(authentication(authenticationFor(accountId)))
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private UUID group(int suffix, String createdAt, String name) {
        var groupId = UUID.fromString("72000000-0000-4000-8000-" + String.format("%012d", suffix));
        jdbcClient.sql("""
                        INSERT INTO group_account (
                            group_id, name, description, status, created_by_account_id,
                            version, created_at, updated_at
                        )
                        VALUES (:groupId, :name, '', 'ACTIVE', :ownerId, 1, :createdAt, :createdAt)
                        """)
                .param("groupId", groupId)
                .param("name", name)
                .param("ownerId", OWNER_ID)
                .param("createdAt", OffsetDateTime.parse(createdAt))
                .update();
        return groupId;
    }

    private void membership(UUID groupId, UUID accountId, String role, String status) {
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at, ended_at)
                        VALUES (:groupId, :accountId, :role, :status, statement_timestamp(),
                                CASE WHEN :status = 'ACTIVE' THEN NULL ELSE statement_timestamp() END)
                        """)
                .param("groupId", groupId)
                .param("accountId", accountId)
                .param("role", role)
                .param("status", status)
                .update();
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId) {
        return new TestingAuthenticationToken(new AuthenticatedActor(accountId, Set.of()), null, Set.of());
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
