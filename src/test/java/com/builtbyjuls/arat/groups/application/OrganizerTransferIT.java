package com.builtbyjuls.arat.groups.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.CreateGroupCommand;
import com.builtbyjuls.arat.groups.api.OrganizerTransferException;
import com.builtbyjuls.arat.groups.api.TransferOrganizerCommand;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.identity.testing.AratAccountFixture;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrganizerTransferIT extends PostgreSqlIntegrationTest {

    private static final String CORRELATION_ID = "organizer-transfer-test-123";
    private static final UUID OWNER_ID = AratAccountFixture.OWNER.account().accountId();
    private static final UUID MEMBER_ID = AratAccountFixture.MEMBER.account().accountId();
    private static final UUID OUTSIDER_ID = AratAccountFixture.OUTSIDER.account().accountId();

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private GroupCreationService groupCreationService;

    @Autowired
    private OrganizerTransferService organizerTransferService;

    private MockMvc mockMvc;
    private UUID groupId;

    @BeforeEach
    void prepareDatabase() {
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM group_invitation").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account").update();
        for (var account : AratAccountFixture.values()) {
            jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                    .param("accountId", account.account().accountId())
                    .param("displayName", account.account().displayName())
                    .update();
        }
        groupId = groupCreationService.create(new CreateGroupCommand(
                OWNER_ID, "organizer-transfer-create", "Weekend badminton", "", CORRELATION_ID)).groupId();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void transfersOneOrganizerToAnActiveMemberAndRecordsBoundedAuditFacts() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");

        transferAs(OWNER_ID, MEMBER_ID, "\"1\"", "transfer-one")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.groupId").value(groupId.toString()))
                .andExpect(jsonPath("$.previousOrganizerAccountId").value(OWNER_ID.toString()))
                .andExpect(jsonPath("$.organizerAccountId").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.groupVersion").value(2));

        assertThat(membershipRole(OWNER_ID)).isEqualTo("MEMBER");
        assertThat(membershipRole(MEMBER_ID)).isEqualTo("ORGANIZER");
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(auditCount()).isOne();
        assertThat(auditActorId()).isEqualTo(OWNER_ID);
        assertThat(auditGroupId()).isEqualTo(groupId);
        assertThat(auditSubjectId()).isEqualTo(MEMBER_ID);
        assertThat(auditMetadata()).contains("targetAccountId").contains(MEMBER_ID.toString());
    }

    @Test
    void leavesOtherOrganizersUnchanged() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");
        insertActiveMembership(OUTSIDER_ID, "ORGANIZER");

        transferAs(OWNER_ID, MEMBER_ID, "\"1\"", "transfer-multiple")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));

        assertThat(membershipRole(OWNER_ID)).isEqualTo("MEMBER");
        assertThat(membershipRole(MEMBER_ID)).isEqualTo("ORGANIZER");
        assertThat(membershipRole(OUTSIDER_ID)).isEqualTo("ORGANIZER");
        assertThat(activeOrganizerCount()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidTargetsWithoutChangingMembership() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");
        insertActiveMembership(OUTSIDER_ID, "ORGANIZER");

        transferAs(OWNER_ID, OWNER_ID, "\"1\"", "transfer-self")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        transferAs(OWNER_ID, UUID.randomUUID(), "\"1\"", "transfer-missing")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        jdbcClient.sql("UPDATE group_membership SET status = 'LEFT', ended_at = statement_timestamp() WHERE group_id = :groupId AND account_id = :accountId")
                .param("groupId", groupId).param("accountId", MEMBER_ID).update();
        transferAs(OWNER_ID, MEMBER_ID, "\"1\"", "transfer-inactive")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        transferAs(OWNER_ID, OUTSIDER_ID, "\"1\"", "transfer-organizer")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_ORGANIZER"));

        assertThat(membershipRole(OWNER_ID)).isEqualTo("ORGANIZER");
        assertThat(membershipRole(OUTSIDER_ID)).isEqualTo("ORGANIZER");
        assertThat(groupVersion()).isOne();
        assertThat(auditCount()).isZero();
    }

    @Test
    void protectsPrivateGroupsAndRequiresOrganizerAuthority() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");

        transferAs(MEMBER_ID, OWNER_ID, "\"1\"", "transfer-non-organizer")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        transferAs(OUTSIDER_ID, MEMBER_ID, "\"1\"", "transfer-outsider")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
    }

    @Test
    void enforcesEveryIfMatchFailureWithoutChangingMembership() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");

        mockMvc.perform(post("/api/v1/groups/{groupId}/organizer-transfer", groupId)
                        .with(authentication(authenticationFor(OWNER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetAccountId\":\"" + MEMBER_ID + "\"}")
                        .header("Idempotency-Key", "transfer-missing-if-match")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        transferAs(OWNER_ID, MEMBER_ID, "1", "transfer-malformed-if-match")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRECONDITION"));
        transferAs(OWNER_ID, MEMBER_ID, "\"2\"", "transfer-stale-if-match")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        assertThat(membershipRole(OWNER_ID)).isEqualTo("ORGANIZER");
        assertThat(membershipRole(MEMBER_ID)).isEqualTo("MEMBER");
        assertThat(groupVersion()).isOne();
        assertThat(auditCount()).isZero();
    }

    @Test
    void exactReplayReturnsOriginalBodyAndEtagBeforeStaleVersionValidation() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");
        insertActiveMembership(OUTSIDER_ID, "MEMBER");

        transferAs(OWNER_ID, MEMBER_ID, "\"1\"", "transfer-replay")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));
        jdbcClient.sql("UPDATE group_account SET version = version + 1 WHERE group_id = :groupId")
                .param("groupId", groupId).update();
        transferAs(OWNER_ID, MEMBER_ID, "\"1\"", "transfer-replay")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.groupVersion").value(2));
        transferAs(OWNER_ID, OUTSIDER_ID, "\"1\"", "transfer-replay")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(groupVersion()).isEqualTo(3);
        assertThat(auditCount()).isOne();
    }

    @Test
    void rollsBackRoleChangesVersionAndIdempotencyCompletionWhenAuditAppendFails() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");
        jdbcClient.sql("""
                CREATE FUNCTION test_fail_organizer_transfer_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced organizer transfer audit failure'; END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_organizer_transfer_audit BEFORE INSERT ON audit_event
                FOR EACH ROW WHEN (NEW.action = 'group.organizer.transferred')
                EXECUTE FUNCTION test_fail_organizer_transfer_audit()
                """).update();
        try {
            transferAs(OWNER_ID, MEMBER_ID, "\"1\"", "transfer-rollback")
                    .andExpect(status().isInternalServerError());

            assertThat(membershipRole(OWNER_ID)).isEqualTo("ORGANIZER");
            assertThat(membershipRole(MEMBER_ID)).isEqualTo("MEMBER");
            assertThat(groupVersion()).isOne();
            assertThat(auditCount()).isZero();
            assertThat(idempotencyCount("transfer-rollback")).isZero();
        } finally {
            jdbcClient.sql("DROP TRIGGER fail_organizer_transfer_audit ON audit_event").update();
            jdbcClient.sql("DROP FUNCTION test_fail_organizer_transfer_audit()").update();
        }
    }

    @Test
    void concurrentTransfersFromOneCallerHaveOneVersionValidTransition() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");
        insertActiveMembership(OUTSIDER_ID, "MEMBER");
        var barrier = new CyclicBarrier(2);
        var first = new TransferOrganizerCommand(OWNER_ID, groupId, MEMBER_ID, 1, "transfer-race-member", CORRELATION_ID);
        var second = new TransferOrganizerCommand(OWNER_ID, groupId, OUTSIDER_ID, 1, "transfer-race-outsider", CORRELATION_ID);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(
                    executor.submit(() -> transferOrReturnFailureAfterBarrier(first, barrier)),
                    executor.submit(() -> transferOrReturnFailureAfterBarrier(second, barrier)));
            var outcomes = List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(OrganizerTransferException.class::isInstance)
                    .map(OrganizerTransferException.class::cast)
                    .map(OrganizerTransferException::reason))
                    .containsExactly(OrganizerTransferException.Reason.PRECONDITION_FAILED);
        }
        assertThat(activeOrganizerCount()).isOne();
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(auditCount()).isOne();
    }

    @Test
    void publishesOrganizerTransferInExecutableOpenApiAndRequiresAnIdempotencyKey() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/organizer-transfer'].post.operationId").value("transferGroupOrganizer"))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/organizer-transfer'].post.parameters[?(@.name == 'If-Match')].required", contains(true)))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/organizer-transfer'].post.responses['200'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/organizer-transfer'].post.responses['428']").exists());
        mockMvc.perform(post("/api/v1/groups/{groupId}/organizer-transfer", groupId)
                        .with(authentication(authenticationFor(OWNER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetAccountId\":\"" + MEMBER_ID + "\"}")
                        .header("If-Match", "\"1\"")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    private org.springframework.test.web.servlet.ResultActions transferAs(
            UUID actorId, UUID targetAccountId, String ifMatch, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/{groupId}/organizer-transfer", groupId)
                .with(authentication(authenticationFor(actorId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetAccountId\":\"" + targetAccountId + "\"}")
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId) {
        return new TestingAuthenticationToken(new AuthenticatedActor(accountId, java.util.Set.of()), null, "ROLE_USER");
    }

    private void insertActiveMembership(UUID accountId, String role) {
        jdbcClient.sql("""
                INSERT INTO group_membership (group_id, account_id, role, status, joined_at, ended_at)
                VALUES (:groupId, :accountId, :role, 'ACTIVE', statement_timestamp(), NULL)
                ON CONFLICT (group_id, account_id) DO UPDATE
                SET role = EXCLUDED.role, status = 'ACTIVE', joined_at = statement_timestamp(), ended_at = NULL
                """)
                .param("groupId", groupId).param("accountId", accountId).param("role", role).update();
    }

    private String membershipRole(UUID accountId) {
        return jdbcClient.sql("SELECT role FROM group_membership WHERE group_id = :groupId AND account_id = :accountId")
                .param("groupId", groupId).param("accountId", accountId).query(String.class).single();
    }

    private long groupVersion() {
        return jdbcClient.sql("SELECT version FROM group_account WHERE group_id = :groupId")
                .param("groupId", groupId).query(Long.class).single();
    }

    private long auditCount() {
        return jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = 'group.organizer.transferred'")
                .query(Long.class).single();
    }

    private UUID auditActorId() {
        return jdbcClient.sql("SELECT actor_id FROM audit_event WHERE action = 'group.organizer.transferred'")
                .query(UUID.class).single();
    }

    private UUID auditGroupId() {
        return jdbcClient.sql("SELECT group_id FROM audit_event WHERE action = 'group.organizer.transferred'")
                .query(UUID.class).single();
    }

    private UUID auditSubjectId() {
        return jdbcClient.sql("SELECT subject_id FROM audit_event WHERE action = 'group.organizer.transferred'")
                .query(UUID.class).single();
    }

    private String auditMetadata() {
        return jdbcClient.sql("SELECT metadata::TEXT FROM audit_event WHERE action = 'group.organizer.transferred'")
                .query(String.class).single();
    }

    private long idempotencyCount(String key) {
        return jdbcClient.sql("SELECT count(*) FROM idempotency_record WHERE operation = :operation AND idempotency_key = :key")
                .param("operation", OrganizerTransferService.TRANSFER_ORGANIZER_OPERATION).param("key", key)
                .query(Long.class).single();
    }

    private long activeOrganizerCount() {
        return jdbcClient.sql("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND role = 'ORGANIZER' AND status = 'ACTIVE'")
                .param("groupId", groupId).query(Long.class).single();
    }

    private Object transferOrReturnFailureAfterBarrier(TransferOrganizerCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return organizerTransferService.transfer(command);
        } catch (OrganizerTransferException exception) {
            return exception;
        }
    }
}
