package com.builtbyjuls.arat.groups.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.CreateGroupCommand;
import com.builtbyjuls.arat.groups.api.LeaveGroupCommand;
import com.builtbyjuls.arat.groups.api.MembershipExitException;
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
class MembershipExitIT extends PostgreSqlIntegrationTest {

    private static final String CORRELATION_ID = "membership-exit-test-123";
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
    private MembershipExitService membershipExitService;

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
                OWNER_ID, "membership-exit-create", "Weekend badminton", "", CORRELATION_ID)).groupId();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void memberLeavesOnceReplaysExactlyAndLosesGroupAccess() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");

        leaveAs(MEMBER_ID, "leave-member")
                .andExpect(status().isNoContent())
                .andExpect(header().string("ETag", "\"2\""));
        leaveAs(MEMBER_ID, "leave-member")
                .andExpect(status().isNoContent())
                .andExpect(header().string("ETag", "\"2\""));
        leaveAs(MEMBER_ID, "leave-member-new-key")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/groups/{groupId}", groupId)
                        .with(authentication(authenticationFor(MEMBER_ID)))
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));

        assertThat(membershipStatus(MEMBER_ID)).isEqualTo("LEFT");
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(auditCount("group.membership.left")).isOne();
        assertThat(idempotencyCount(MembershipExitService.LEAVE_OPERATION, "leave-member")).isOne();
    }

    @Test
    void organizerRemovesAnotherActiveMemberAndAdvancesTheVersionOnce() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");

        removeAs(OWNER_ID, MEMBER_ID, "remove-member")
                .andExpect(status().isNoContent())
                .andExpect(header().string("ETag", "\"2\""));
        removeAs(OWNER_ID, MEMBER_ID, "remove-member")
                .andExpect(status().isNoContent())
                .andExpect(header().string("ETag", "\"2\""));

        assertThat(membershipStatus(MEMBER_ID)).isEqualTo("REMOVED");
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(auditCount("group.membership.removed")).isOne();
        assertThat(idempotencyCount(MembershipExitService.REMOVE_OPERATION, "remove-member")).isOne();
    }

    @Test
    void protectsPrivateGroupsAndRejectsInvalidRemovalRoutes() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");

        removeAs(MEMBER_ID, OWNER_ID, "remove-non-organizer")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        removeAs(OWNER_ID, OWNER_ID, "remove-self")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        removeAs(OWNER_ID, UUID.randomUUID(), "remove-missing")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        jdbcClient.sql("UPDATE group_membership SET status = 'LEFT', ended_at = statement_timestamp() WHERE group_id = :groupId AND account_id = :accountId")
                .param("groupId", groupId).param("accountId", MEMBER_ID).update();
        removeAs(OWNER_ID, MEMBER_ID, "remove-inactive")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        removeAs(OUTSIDER_ID, MEMBER_ID, "remove-private")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
    }

    @Test
    void finalOrganizerCannotLeave() throws Exception {
        leaveAs(OWNER_ID, "leave-final-organizer")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FINAL_ORGANIZER_REQUIRED"));

        assertThat(membershipStatus(OWNER_ID)).isEqualTo("ACTIVE");
        assertThat(groupVersion()).isOne();
        assertThat(auditCount("group.membership.left")).isZero();
    }

    @Test
    void rejectsConflictingReuseAndRollsBackMembershipExitWhenAuditAppendFails() throws Exception {
        insertActiveMembership(MEMBER_ID, "MEMBER");
        insertActiveMembership(OUTSIDER_ID, "MEMBER");
        removeAs(OWNER_ID, MEMBER_ID, "remove-conflicting-key").andExpect(status().isNoContent());
        removeAs(OWNER_ID, OUTSIDER_ID, "remove-conflicting-key")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        jdbcClient.sql("""
                CREATE FUNCTION test_fail_membership_exit_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced membership exit audit failure'; END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_membership_exit_audit BEFORE INSERT ON audit_event
                FOR EACH ROW WHEN (NEW.action = 'group.membership.removed')
                EXECUTE FUNCTION test_fail_membership_exit_audit()
                """).update();

        removeAs(OWNER_ID, OUTSIDER_ID, "remove-rollback").andExpect(status().isInternalServerError());

        assertThat(membershipStatus(OUTSIDER_ID)).isEqualTo("ACTIVE");
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(auditCount("group.membership.removed")).isOne();
        assertThat(idempotencyCount(MembershipExitService.REMOVE_OPERATION, "remove-rollback")).isZero();
        jdbcClient.sql("DROP TRIGGER fail_membership_exit_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION test_fail_membership_exit_audit()").update();
    }

    @Test
    void concurrentOrganizerLeavesPreserveOneActiveOrganizer() throws Exception {
        insertActiveMembership(MEMBER_ID, "ORGANIZER");
        var barrier = new CyclicBarrier(2);
        var first = new LeaveGroupCommand(OWNER_ID, groupId, "leave-race-owner", CORRELATION_ID);
        var second = new LeaveGroupCommand(MEMBER_ID, groupId, "leave-race-member", CORRELATION_ID);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(
                    executor.submit(() -> leaveOrReturnFailureAfterBarrier(first, barrier)),
                    executor.submit(() -> leaveOrReturnFailureAfterBarrier(second, barrier)));
            var outcomes = List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(String.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(MembershipExitException.class::isInstance)
                    .map(MembershipExitException.class::cast)
                    .map(MembershipExitException::reason))
                    .containsExactly(MembershipExitException.Reason.FINAL_ORGANIZER_REQUIRED);
        }
        assertThat(activeOrganizerCount()).isOne();
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(auditCount("group.membership.left")).isOne();
    }

    @Test
    void publishesMembershipExitEndpointsInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/members/me'].delete.operationId").value("leaveGroup"))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/members/me'].delete.responses['204'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/members/me'].delete.responses['422']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/members/{accountId}'].delete.operationId").value("removeGroupMember"))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/members/{accountId}'].delete.responses['422']").exists());
    }

    @Test
    void validatesMembershipExitIdempotencyHeaders() throws Exception {
        mockMvc.perform(delete("/api/v1/groups/{groupId}/members/me", groupId)
                        .with(authentication(authenticationFor(OWNER_ID)))
                        .header("Idempotency-Key", " ")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private org.springframework.test.web.servlet.ResultActions leaveAs(UUID actorId, String key) throws Exception {
        return mockMvc.perform(delete("/api/v1/groups/{groupId}/members/me", groupId)
                .with(authentication(authenticationFor(actorId)))
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private org.springframework.test.web.servlet.ResultActions removeAs(UUID actorId, UUID targetAccountId, String key) throws Exception {
        return mockMvc.perform(delete("/api/v1/groups/{groupId}/members/{accountId}", groupId, targetAccountId)
                .with(authentication(authenticationFor(actorId)))
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

    private String membershipStatus(UUID accountId) {
        return jdbcClient.sql("SELECT status FROM group_membership WHERE group_id = :groupId AND account_id = :accountId")
                .param("groupId", groupId).param("accountId", accountId).query(String.class).single();
    }

    private long groupVersion() {
        return jdbcClient.sql("SELECT version FROM group_account WHERE group_id = :groupId")
                .param("groupId", groupId).query(Long.class).single();
    }

    private long auditCount(String action) {
        return jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = :action")
                .param("action", action).query(Long.class).single();
    }

    private long idempotencyCount(String operation, String key) {
        return jdbcClient.sql("SELECT count(*) FROM idempotency_record WHERE operation = :operation AND idempotency_key = :key")
                .param("operation", operation).param("key", key).query(Long.class).single();
    }

    private long activeOrganizerCount() {
        return jdbcClient.sql("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND role = 'ORGANIZER' AND status = 'ACTIVE'")
                .param("groupId", groupId).query(Long.class).single();
    }

    private Object leaveOrReturnFailureAfterBarrier(LeaveGroupCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return membershipExitService.leave(command);
        } catch (MembershipExitException exception) {
            return exception;
        }
    }
}
