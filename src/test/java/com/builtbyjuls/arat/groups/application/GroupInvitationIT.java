package com.builtbyjuls.arat.groups.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.CreateGroupCommand;
import com.builtbyjuls.arat.groups.api.CreateInvitationCommand;
import com.builtbyjuls.arat.groups.api.InvitationException;
import com.builtbyjuls.arat.groups.api.InvitationRepresentation;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.identity.testing.AratAccountFixture;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.UUID;
import java.util.List;
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
class GroupInvitationIT extends PostgreSqlIntegrationTest {

    private static final String CORRELATION_ID = "group-invitation-test-123";
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
    private InvitationService invitationService;

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
                OWNER_ID, "group-invitation-create", "Weekend badminton", "", CORRELATION_ID)).groupId();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void organizerCreatesDigestOnlyInvitationAndExactReplayReconstructsTheToken() throws Exception {
        var first = createAs(OWNER_ID, "invite-create-1", MEMBER_ID, 24)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/v1/groups/[0-9a-f-]{36}/invites/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.groupId").value(groupId.toString()))
                .andExpect(jsonPath("$.inviteeAccountId").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse();
        var replay = createAs(OWNER_ID, "invite-create-1", MEMBER_ID, 24)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", first.getHeader("Location")))
                .andReturn().getResponse();

        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(jdbcClient.sql("SELECT octet_length(nonce) FROM group_invitation").query(Integer.class).single()).isEqualTo(32);
        assertThat(jdbcClient.sql("SELECT token_digest FROM group_invitation").query(String.class).single()).matches("[0-9a-f]{64}");
        assertThat(jdbcClient.sql("SELECT token_digest FROM group_invitation").query(String.class).single())
                .doesNotContain(jsonValue(first.getContentAsString(), "token"));
        assertThat(jdbcClient.sql("SELECT replay_state::TEXT FROM idempotency_record WHERE operation = 'groups.invites.create'")
                .query(String.class).single()).doesNotContain("token");
        assertThat(jdbcClient.sql("SELECT count(*) FROM group_invitation").query(Long.class).single()).isOne();
        assertThat(jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.created'")
                .query(Long.class).single()).isOne();
        assertThat(jdbcClient.sql("SELECT version FROM group_account WHERE group_id = :groupId")
                .param("groupId", groupId).query(Long.class).single()).isOne();
    }

    @Test
    void enforcesInvitationAuthorizationAndMembershipRulesWithoutLeakingPrivateGroups() throws Exception {
        createAs(OUTSIDER_ID, "invite-outsider", MEMBER_ID, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        insertMembership(MEMBER_ID, "MEMBER", "ACTIVE", null);
        createAs(MEMBER_ID, "invite-member", OUTSIDER_ID, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        createAs(OWNER_ID, "invite-active-member", MEMBER_ID, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));

        jdbcClient.sql("UPDATE group_membership SET status = 'LEFT', ended_at = statement_timestamp() WHERE group_id = :groupId AND account_id = :accountId")
                .param("groupId", groupId).param("accountId", MEMBER_ID).update();
        createAs(OWNER_ID, "invite-former-member", MEMBER_ID, null).andExpect(status().isCreated());
    }

    @Test
    void validatesExpiryAndKnownAccountAfterOrganizerAuthorization() throws Exception {
        createAs(OWNER_ID, "invite-invalid-expiry", MEMBER_ID, 169)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        createAs(OWNER_ID, "invite-unknown", UUID.randomUUID(), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbcClient.sql("SELECT count(*) FROM group_invitation").query(Long.class).single()).isZero();
    }

    @Test
    void expiresOldPendingInvitationsAndRejectsAnotherUnexpiredInvitation() throws Exception {
        var first = createAs(OWNER_ID, "invite-pending-1", MEMBER_ID, 1)
                .andExpect(status().isCreated()).andReturn().getResponse();
        createAs(OWNER_ID, "invite-pending-2", MEMBER_ID, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_PENDING"));

        jdbcClient.sql("UPDATE group_invitation SET expires_at = statement_timestamp() - INTERVAL '1 second' WHERE invite_id = :inviteId")
                .param("inviteId", UUID.fromString(jsonValue(first.getContentAsString(), "inviteId"))).update();
        createAs(OWNER_ID, "invite-after-expiry", MEMBER_ID, null).andExpect(status().isCreated());
        assertThat(jdbcClient.sql("SELECT count(*) FROM group_invitation WHERE state = 'EXPIRED'").query(Long.class).single()).isOne();
    }

    @Test
    void usesTheFrozenDefaultExpiryHours() throws Exception {
        createAs(OWNER_ID, "invite-default-expiry", MEMBER_ID, null).andExpect(status().isCreated());

        assertThat(jdbcClient.sql("SELECT expires_at - created_at = INTERVAL '72 hours' FROM group_invitation")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void revokesOnceReplaysAndLeavesUnavailableInvitationsPrivate() throws Exception {
        var response = createAs(OWNER_ID, "invite-revoke-create", MEMBER_ID, null)
                .andExpect(status().isCreated()).andReturn().getResponse();
        var inviteId = UUID.fromString(jsonValue(response.getContentAsString(), "inviteId"));

        revokeAs(OWNER_ID, "invite-revoke-1", inviteId).andExpect(status().isNoContent());
        revokeAs(OWNER_ID, "invite-revoke-1", inviteId).andExpect(status().isNoContent());
        revokeAs(OWNER_ID, "invite-revoke-2", inviteId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_UNAVAILABLE"));
        revokeAs(OUTSIDER_ID, "invite-revoke-outsider", inviteId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        assertThat(jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.revoked'")
                .query(Long.class).single()).isOne();
    }

    @Test
    void rejectsEveryNewKeyUnavailableInvitationLifecycleState() throws Exception {
        revokeAs(OWNER_ID, "invite-missing", UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_UNAVAILABLE"));

        var expiredPending = UUID.fromString(jsonValue(createAs(OWNER_ID, "invite-expired-pending", MEMBER_ID, null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "inviteId"));
        jdbcClient.sql("UPDATE group_invitation SET expires_at = statement_timestamp() - INTERVAL '1 second' WHERE invite_id = :inviteId")
                .param("inviteId", expiredPending).update();
        revokeAs(OWNER_ID, "invite-expired-pending-revoke", expiredPending)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_UNAVAILABLE"));
        assertThat(jdbcClient.sql("SELECT state FROM group_invitation WHERE invite_id = :inviteId")
                .param("inviteId", expiredPending).query(String.class).single()).isEqualTo("PENDING");

        var consumed = UUID.fromString(jsonValue(createAs(OWNER_ID, "invite-consumed", MEMBER_ID, null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "inviteId"));
        jdbcClient.sql("UPDATE group_invitation SET state = 'CONSUMED' WHERE invite_id = :inviteId")
                .param("inviteId", consumed).update();
        revokeAs(OWNER_ID, "invite-consumed-revoke", consumed)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_UNAVAILABLE"));
    }

    @Test
    void rejectsConflictingIdempotencyReuseAndRollsBackInvitationWithAuditFailure() throws Exception {
        createAs(OWNER_ID, "invite-conflicting-key", MEMBER_ID, 24).andExpect(status().isCreated());
        createAs(OWNER_ID, "invite-conflicting-key", OUTSIDER_ID, 24)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        jdbcClient.sql("""
                CREATE FUNCTION test_fail_invitation_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced audit failure'; END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_invitation_audit BEFORE INSERT ON audit_event
                FOR EACH ROW WHEN (NEW.action = 'group.invitation.created')
                EXECUTE FUNCTION test_fail_invitation_audit()
                """).update();
        createAs(OWNER_ID, "invite-rollback", OUTSIDER_ID, null).andExpect(status().isInternalServerError());

        assertThat(jdbcClient.sql("SELECT count(*) FROM group_invitation WHERE invitee_account_id = :accountId")
                .param("accountId", OUTSIDER_ID).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM idempotency_record WHERE idempotency_key = 'invite-rollback'")
                .query(Long.class).single()).isZero();
        jdbcClient.sql("DROP TRIGGER fail_invitation_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION test_fail_invitation_audit()").update();
    }

    @Test
    void rollsBackRevocationAndItsIdempotencyCompletionWhenAuditFails() throws Exception {
        var inviteId = UUID.fromString(jsonValue(createAs(OWNER_ID, "invite-revoke-rollback-create", MEMBER_ID, null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "inviteId"));
        jdbcClient.sql("""
                CREATE FUNCTION test_fail_invitation_revoke_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced revoke audit failure'; END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_invitation_revoke_audit BEFORE INSERT ON audit_event
                FOR EACH ROW WHEN (NEW.action = 'group.invitation.revoked')
                EXECUTE FUNCTION test_fail_invitation_revoke_audit()
                """).update();

        revokeAs(OWNER_ID, "invite-revoke-rollback", inviteId).andExpect(status().isInternalServerError());

        assertThat(jdbcClient.sql("SELECT state FROM group_invitation WHERE invite_id = :inviteId")
                .param("inviteId", inviteId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.revoked'")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM idempotency_record WHERE idempotency_key = 'invite-revoke-rollback'")
                .query(Long.class).single()).isZero();
        jdbcClient.sql("DROP TRIGGER fail_invitation_revoke_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION test_fail_invitation_revoke_audit()").update();
    }

    @Test
    void concurrentExactCreateRetriesReplayOneInvitation() throws Exception {
        var barrier = new CyclicBarrier(2);
        var command = new CreateInvitationCommand(
                OWNER_ID, groupId, MEMBER_ID, 24, "invite-concurrent-replay", CORRELATION_ID);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(
                    executor.submit(() -> createAfterBarrier(command, barrier)),
                    executor.submit(() -> createAfterBarrier(command, barrier)));
            var invitations = List.of(
                    results.get(0).get(10, TimeUnit.SECONDS),
                    results.get(1).get(10, TimeUnit.SECONDS));
            assertThat(invitations).extracting(InvitationRepresentation::inviteId)
                    .containsOnly(invitations.getFirst().inviteId());
            assertThat(invitations).extracting(InvitationRepresentation::token)
                    .containsOnly(invitations.getFirst().token());
        }
        assertThat(jdbcClient.sql("SELECT count(*) FROM group_invitation WHERE state = 'PENDING'").query(Long.class).single()).isOne();
        assertThat(jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.created'").query(Long.class).single()).isOne();
        assertThat(jdbcClient.sql("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.invites.create'").query(Long.class).single()).isOne();
    }

    @Test
    void concurrentCreatorsExpireOneOldInvitationAndLeaveOneNewPendingInvitation() throws Exception {
        var oldInviteId = UUID.fromString(jsonValue(createAs(OWNER_ID, "invite-race-old", MEMBER_ID, null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "inviteId"));
        jdbcClient.sql("UPDATE group_invitation SET expires_at = statement_timestamp() - INTERVAL '1 second' WHERE invite_id = :inviteId")
                .param("inviteId", oldInviteId).update();
        var barrier = new CyclicBarrier(2);
        var first = new CreateInvitationCommand(OWNER_ID, groupId, MEMBER_ID, 24, "invite-race-first", CORRELATION_ID);
        var second = new CreateInvitationCommand(OWNER_ID, groupId, MEMBER_ID, 24, "invite-race-second", CORRELATION_ID);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(
                    executor.submit(() -> createOrReturnFailureAfterBarrier(first, barrier)),
                    executor.submit(() -> createOrReturnFailureAfterBarrier(second, barrier)));
            var outcomes = List.of(
                    results.get(0).get(10, TimeUnit.SECONDS),
                    results.get(1).get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(InvitationRepresentation.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(InvitationException.class::isInstance)
                    .map(InvitationException.class::cast)
                    .map(InvitationException::reason))
                    .containsExactly(InvitationException.Reason.INVITATION_ALREADY_PENDING);
        }
        assertThat(jdbcClient.sql("SELECT count(*) FROM group_invitation WHERE state = 'EXPIRED'").query(Long.class).single()).isOne();
        assertThat(jdbcClient.sql("SELECT count(*) FROM group_invitation WHERE state = 'PENDING'").query(Long.class).single()).isOne();
        assertThat(jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.created'").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.invites.create'").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void publishesInvitationEndpointsInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/invites'].post.operationId").value("createGroupInvitation"))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/invites'].post.responses['201'].headers.Location").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/invites/{inviteId}'].delete.operationId").value("revokeGroupInvitation"))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/invites/{inviteId}'].delete.responses['204']").exists());
    }

    private org.springframework.test.web.servlet.ResultActions createAs(UUID actorId, String key, UUID inviteeId, Integer expiryHours) throws Exception {
        var body = "{\"inviteeAccountId\":\"" + inviteeId + "\"" + (expiryHours == null ? "" : ",\"expiryHours\":" + expiryHours) + "}";
        return mockMvc.perform(post("/api/v1/groups/{groupId}/invites", groupId)
                .with(authentication(authenticationFor(actorId)))
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions revokeAs(UUID actorId, String key, UUID inviteId) throws Exception {
        return mockMvc.perform(delete("/api/v1/groups/{groupId}/invites/{inviteId}", groupId, inviteId)
                .with(authentication(authenticationFor(actorId)))
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId) {
        return new TestingAuthenticationToken(new AuthenticatedActor(accountId, java.util.Set.of()), null, "ROLE_USER");
    }

    private void insertMembership(UUID accountId, String role, String status, String endedAt) {
        jdbcClient.sql("""
                INSERT INTO group_membership (group_id, account_id, role, status, joined_at, ended_at)
                VALUES (:groupId, :accountId, :role, :status, statement_timestamp(), """ + (endedAt == null ? "NULL" : endedAt) + ")")
                .param("groupId", groupId).param("accountId", accountId)
                .param("role", role).param("status", status).update();
    }

    private String jsonValue(String json, String field) throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(json).path(field).asString();
    }

    private InvitationRepresentation createAfterBarrier(CreateInvitationCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return invitationService.create(command);
    }

    private Object createOrReturnFailureAfterBarrier(CreateInvitationCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return invitationService.create(command);
        } catch (InvitationException exception) {
            return exception;
        }
    }
}
