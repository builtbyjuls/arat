package com.builtbyjuls.arat.groups.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.CreateGroupCommand;
import com.builtbyjuls.arat.identity.testing.WithAratActor;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GroupCreationIT extends PostgreSqlIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String CORRELATION_ID = "group-create-test-123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private GroupCreationService groupCreationService;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        removeAuditFailureTrigger();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id = :accountId")
                .param("accountId", OWNER_ID)
                .update();
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, 'Group owner')")
                .param("accountId", OWNER_ID)
                .update();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void removeAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_group_created_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_group_created_audit()").update();
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000001")
    void createsOnePrivateGroupWithItsOrganizerAndSanitizedAuditEvent() throws Exception {
        var response = create("create-group-1", "Weekend badminton", "Saturday court planning")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/v1/groups/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.name").value("Weekend badminton"))
                .andExpect(jsonPath("$.description").value("Saturday court planning"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn()
                .getResponse();

        var groupId = UUID.fromString(objectValue(response.getContentAsString(), "groupId"));
        assertThat(count("SELECT count(*) FROM group_account")).isOne();
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :accountId AND role = 'ORGANIZER' AND status = 'ACTIVE'", groupId))
                .isOne();
        assertThat(jdbcClient.sql("""
                        SELECT metadata::TEXT
                        FROM audit_event
                        WHERE action = 'group.created'
                          AND subject_type = 'group'
                          AND subject_id = :groupId
                          AND group_id = :groupId
                        """)
                .param("groupId", groupId)
                .query(String.class)
                .single()).isEqualTo("{}");
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.create' AND state = 'COMPLETED'"))
                .isOne();
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000001")
    void exactHttpReplayReturnsTheOriginalGroupWithoutMoreBusinessEffects() throws Exception {
        var first = create("create-group-replay", "Weekend badminton", null)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse();
        var replay = create("create-group-replay", "Weekend badminton", null)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andExpect(header().string("Location", first.getHeader("Location")))
                .andReturn()
                .getResponse();

        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(count("SELECT count(*) FROM group_account")).isOne();
        assertThat(count("SELECT count(*) FROM group_membership")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.created' ")).isOne();
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000001")
    void rejectsDifferentPayloadReuseWithoutBusinessSideEffects() throws Exception {
        create("create-group-conflict", "Weekend badminton", null).andExpect(status().isCreated());

        create("create-group-conflict", "Sunday badminton", null)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(count("SELECT count(*) FROM group_account")).isOne();
        assertThat(count("SELECT count(*) FROM group_membership")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isOne();
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000001")
    void mapsValidationAndMissingIdempotencyKeyToProblemResponses() throws Exception {
        mockMvc.perform(post("/api/v1/groups")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Weekend badminton\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        create("invalid-group", "", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("name"));
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000001")
    void rollsBackTheGroupMembershipAuditAndIdempotencyRecordWhenAuditFails() throws Exception {
        jdbcClient.sql("""
                        CREATE FUNCTION test_fail_group_created_audit()
                        RETURNS trigger
                        LANGUAGE plpgsql
                        AS $$
                        BEGIN
                            RAISE EXCEPTION 'forced audit failure';
                        END;
                        $$
                        """).update();
        jdbcClient.sql("""
                        CREATE TRIGGER fail_group_created_audit
                        BEFORE INSERT ON audit_event
                        FOR EACH ROW
                        WHEN (NEW.action = 'group.created')
                        EXECUTE FUNCTION test_fail_group_created_audit()
                        """).update();

        create("create-group-rollback", "Weekend badminton", null)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(count("SELECT count(*) FROM group_account")).isZero();
        assertThat(count("SELECT count(*) FROM group_membership")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void concurrentExactRetriesCreateOneGroupAndReturnTheSameResource() throws Exception {
        var barrier = new CyclicBarrier(2);
        var command = new CreateGroupCommand(
                OWNER_ID, "create-group-concurrent", "Weekend badminton", "", CORRELATION_ID);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(
                    executor.submit(() -> createAfterBarrier(command, barrier)),
                    executor.submit(() -> createAfterBarrier(command, barrier)));
            var results = List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));

            assertThat(results).extracting(result -> result.groupId()).containsOnly(results.getFirst().groupId());
        }
        assertThat(count("SELECT count(*) FROM group_account")).isOne();
        assertThat(count("SELECT count(*) FROM group_membership")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isOne();
    }

    @Test
    void publishesTheCreateGroupEndpointInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/groups'].post.operationId").value("createGroup"))
                .andExpect(jsonPath("$.paths['/api/v1/groups'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups'].post.responses['201'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups'].post.responses['201'].headers.Location").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups'].post.responses['400'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/groups'].post.responses['401'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/groups'].post.responses['409'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/groups'].post.responses['422'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/groups'].post.security[0].bearerAuth").isArray());
    }

    private org.springframework.test.web.servlet.ResultActions create(String key, String name, String description) throws Exception {
        var body = description == null
                ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}";
        return mockMvc.perform(post("/api/v1/groups")
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private com.builtbyjuls.arat.groups.api.GroupRepresentation createAfterBarrier(
            CreateGroupCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return groupCreationService.create(command);
    }

    private String objectValue(String json, String name) throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(json).path(name).asString();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private long count(String sql, UUID groupId) {
        return jdbcClient.sql(sql)
                .param("groupId", groupId)
                .param("accountId", OWNER_ID)
                .query(Long.class)
                .single();
    }
}
