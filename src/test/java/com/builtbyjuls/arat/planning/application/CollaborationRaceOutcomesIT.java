package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.AcceptInvitationCommand;
import com.builtbyjuls.arat.groups.api.CreateInvitationCommand;
import com.builtbyjuls.arat.groups.api.GroupMembershipRepresentation;
import com.builtbyjuls.arat.groups.api.InvitationException;
import com.builtbyjuls.arat.groups.api.LeaveGroupCommand;
import com.builtbyjuls.arat.groups.api.MembershipExitException;
import com.builtbyjuls.arat.groups.api.RemoveGroupMemberCommand;
import com.builtbyjuls.arat.groups.api.RevokeInvitationCommand;
import com.builtbyjuls.arat.groups.api.TransferOrganizerCommand;
import com.builtbyjuls.arat.groups.application.InvitationService;
import com.builtbyjuls.arat.groups.application.MembershipExitService;
import com.builtbyjuls.arat.groups.application.OrganizerTransferService;
import com.builtbyjuls.arat.groups.api.InvitationRepresentation;
import com.builtbyjuls.arat.marketplace.application.PlanCancellationService;
import com.builtbyjuls.arat.planning.api.CancelPlanCommand;
import com.builtbyjuls.arat.planning.api.CreatePlanCommand;
import com.builtbyjuls.arat.planning.api.PlanCreationException;
import com.builtbyjuls.arat.planning.api.CreatePlanRequest;
import com.builtbyjuls.arat.planning.api.CreatePreferenceCommand;
import com.builtbyjuls.arat.planning.api.CreatePreferenceRequest;
import com.builtbyjuls.arat.planning.api.PlanCancellationException;
import com.builtbyjuls.arat.planning.api.PlanRepresentation;
import com.builtbyjuls.arat.planning.api.PreferenceException;
import com.builtbyjuls.arat.planning.api.PreferenceRepresentation;
import com.builtbyjuls.arat.planning.api.ReplacePreferenceCommand;
import com.builtbyjuls.arat.planning.api.ReplaceRequirementsCommand;
import com.builtbyjuls.arat.planning.api.RequirementReplacementException;
import com.builtbyjuls.arat.planning.api.RequirementReplacementRequest;
import com.builtbyjuls.arat.planning.api.RequirementRepresentation;
import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.planning.domain.Attendance;
import com.builtbyjuls.arat.testing.ConcurrentDatabaseWorkers;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CollaborationRaceOutcomesIT extends PostgreSqlIntegrationTest {

    private static final UUID ORGANIZER_ID = UUID.fromString("81000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("81000000-0000-4000-8000-000000000002");
    private static final UUID SECOND_ORGANIZER_ID = UUID.fromString("81000000-0000-4000-8000-000000000003");
    private static final UUID OUTSIDER_ID = UUID.fromString("81000000-0000-4000-8000-000000000004");
    private static final UUID GROUP_ID = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("83000000-0000-4000-8000-000000000001");
    private static final UUID WINDOW_ID = UUID.fromString("84000000-0000-4000-8000-000000000001");
    private static final String CORRELATION_ID = "collaboration-race";

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private InvitationService invitationService;

    @Autowired
    private MembershipExitService membershipExitService;

    @Autowired
    private OrganizerTransferService organizerTransferService;

    @Autowired
    private PlanCreationService planCreationService;

    @Autowired
    private PlanPreferenceService planPreferenceService;

    @Autowired
    private RequirementReplacementService requirementReplacementService;

    @Autowired
    private PlanCancellationService planCancellationService;

    @BeforeEach
    void prepareDatabase() {
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM planning_preference_ranked_item").update();
        jdbcClient.sql("DELETE FROM planning_preference_selected_window").update();
        jdbcClient.sql("DELETE FROM planning_plan_preference").update();
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM group_invitation").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:organizer, :member, :secondOrganizer, :outsider)")
                .param("organizer", ORGANIZER_ID).param("member", MEMBER_ID)
                .param("secondOrganizer", SECOND_ORGANIZER_ID).param("outsider", OUTSIDER_ID).update();
        jdbcClient.sql("""
                INSERT INTO identity_account (account_id, display_name) VALUES
                    (:organizer, 'Race organizer'), (:member, 'Race member'),
                    (:secondOrganizer, 'Race second organizer'), (:outsider, 'Race outsider')
                """).param("organizer", ORGANIZER_ID).param("member", MEMBER_ID)
                .param("secondOrganizer", SECOND_ORGANIZER_ID).param("outsider", OUTSIDER_ID).update();
        jdbcClient.sql("""
                INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                VALUES (:groupId, 'Race group', '', 'ACTIVE', :organizer)
                """).param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        membership(ORGANIZER_ID, "ORGANIZER");
        membership(MEMBER_ID, "MEMBER");
        insertPlan();
    }

    @Test
    void invitationAcceptanceAndRevocationHaveOneDurableOutcome() throws Exception {
        var invitation = invite();
        var outcomes = race(
                () -> invitationService.accept(new AcceptInvitationCommand(OUTSIDER_ID, invitation.token(), "accept-revoke-accept", CORRELATION_ID)),
                () -> { invitationService.revoke(new RevokeInvitationCommand(ORGANIZER_ID, GROUP_ID, invitation.inviteId(), "accept-revoke-revoke", CORRELATION_ID)); return "revoked"; });

        assertThat(outcomes.getFirst().value()).isInstanceOf(GroupMembershipRepresentation.class);
        assertInvitationUnavailable(outcomes.getLast().failure());
        assertThat(jdbcClient.sql("SELECT state FROM group_invitation WHERE invite_id = :inviteId")
                .param("inviteId", invitation.inviteId()).query(String.class).single()).isEqualTo("CONSUMED");
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :member AND status = 'ACTIVE'", OUTSIDER_ID)).isOne();
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.accepted'")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.revoked'")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.invites.accept' AND state = 'COMPLETED'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.invites.revoke'")).isZero();

        prepareDatabase();
        var revokedInvitation = invite();
        var revokeFirst = race(
                () -> { invitationService.revoke(new RevokeInvitationCommand(ORGANIZER_ID, GROUP_ID, revokedInvitation.inviteId(), "revoke-accept-revoke", CORRELATION_ID)); return "revoked"; },
                () -> invitationService.accept(new AcceptInvitationCommand(OUTSIDER_ID, revokedInvitation.token(), "revoke-accept-accept", CORRELATION_ID)));
        assertThat(revokeFirst.getFirst().value()).isEqualTo("revoked");
        assertInvitationUnavailable(revokeFirst.getLast().failure());
        assertThat(jdbcClient.sql("SELECT state FROM group_invitation WHERE invite_id = :inviteId")
                .param("inviteId", revokedInvitation.inviteId()).query(String.class).single()).isEqualTo("REVOKED");
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :member", OUTSIDER_ID)).isZero();
        assertThat(groupVersion()).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.revoked'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.invites.revoke' AND state = 'COMPLETED'")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.accepted'")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.invites.accept'")).isZero();
    }

    @Test
    void duplicateInvitationAcceptanceHandlesSameAndDifferentKeys() throws Exception {
        var sameKeyInvite = invite();
        var sameKey = race(
                () -> invitationService.accept(new AcceptInvitationCommand(OUTSIDER_ID, sameKeyInvite.token(), "accept-same", CORRELATION_ID)),
                () -> invitationService.accept(new AcceptInvitationCommand(OUTSIDER_ID, sameKeyInvite.token(), "accept-same", CORRELATION_ID)));
        assertThat(sameKey).allSatisfy(outcome -> assertThat(outcome.value()).isInstanceOf(GroupMembershipRepresentation.class));
        assertThat(sameKey.getFirst().value()).isEqualTo(sameKey.getLast().value());
        assertAcceptanceRows(1, 1);

        resetMembershipAndInvitationState();
        var differentKeyInvite = invite();
        var differentKey = race(
                () -> invitationService.accept(new AcceptInvitationCommand(OUTSIDER_ID, differentKeyInvite.token(), "accept-first", CORRELATION_ID)),
                () -> invitationService.accept(new AcceptInvitationCommand(OUTSIDER_ID, differentKeyInvite.token(), "accept-second", CORRELATION_ID)));
        assertThat(differentKey.getFirst().value()).isInstanceOf(GroupMembershipRepresentation.class);
        assertInvitationUnavailable(differentKey.getLast().failure());
        assertAcceptanceRows(1, 1);
    }

    @Test
    void concurrentOrganizerExitsKeepAnActiveOrganizer() throws Exception {
        membership(SECOND_ORGANIZER_ID, "ORGANIZER");
        var outcomes = race(
                () -> membershipExitService.leave(new LeaveGroupCommand(ORGANIZER_ID, GROUP_ID, "leave-owner", CORRELATION_ID)),
                () -> membershipExitService.leave(new LeaveGroupCommand(SECOND_ORGANIZER_ID, GROUP_ID, "leave-second", CORRELATION_ID)));
        assertThat(outcomes.stream().filter(ConcurrentDatabaseWorkers.Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().map(ConcurrentDatabaseWorkers.Outcome::failure)).filteredOn(java.util.Objects::nonNull)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(MembershipExitException.class)
                        .extracting(MembershipExitException.class::cast).extracting(MembershipExitException::reason)
                        .isEqualTo(MembershipExitException.Reason.FINAL_ORGANIZER_REQUIRED));
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND role = 'ORGANIZER' AND status = 'ACTIVE'", null)).isOne();
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.membership.left'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.members.leave' AND state = 'COMPLETED'")).isOne();
    }

    @Test
    void organizerTransferAndRequirementEditRespectTheGroupLock() throws Exception {
        var outcomes = race(
                () -> organizerTransferService.transfer(new TransferOrganizerCommand(ORGANIZER_ID, GROUP_ID, MEMBER_ID, 1, "transfer-requirements", CORRELATION_ID)),
                () -> requirementReplacementService.replace(requirements("Transferred race requirements")));
        assertThat(outcomes.getFirst().value()).isNotNull();
        assertRequirementFailure(outcomes.getLast().failure(), RequirementReplacementException.Reason.FORBIDDEN_ROLE);
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :member AND role = 'ORGANIZER' AND status = 'ACTIVE'", MEMBER_ID)).isOne();
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :organizer AND role = 'MEMBER' AND status = 'ACTIVE'", ORGANIZER_ID)).isOne();
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.organizer.transferred'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.organizer.transfer' AND state = 'COMPLETED'")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced'")).isZero();
        assertThat(planVersion()).isOne();
        assertOriginalRequirementSnapshot();

        prepareDatabase();
        var editFirst = race(
                () -> requirementReplacementService.replace(requirements("Edit before transfer")),
                () -> organizerTransferService.transfer(new TransferOrganizerCommand(ORGANIZER_ID, GROUP_ID, MEMBER_ID, 1, "requirements-transfer", CORRELATION_ID)));
        assertThat(editFirst.getFirst().value()).isInstanceOf(RequirementRepresentation.class);
        assertThat(editFirst.getLast().value()).isNotNull();
        assertThat(groupVersion()).isEqualTo(2);
        assertThat(planVersion()).isEqualTo(2);
        assertRequirementSnapshot("Edit before transfer");
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced'")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.organizer.transferred'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.organizer.transfer' AND state = 'COMPLETED'")).isOne();
    }

    @Test
    void requirementReplacementRacesPreferenceCreationWithoutPartialChildren() throws Exception {
        var outcomes = race(
                () -> requirementReplacementService.replace(requirements("Requirement before preference")),
                () -> planPreferenceService.create(preferenceCreate("race create")));
        assertRequirementPreferenceRace(outcomes);
        assertThat(preferenceRows()).isZero();
        assertRequirementSnapshot("Requirement before preference");

        prepareDatabase();
        var preferenceFirst = race(
                () -> planPreferenceService.create(preferenceCreate("create before requirement")),
                () -> requirementReplacementService.replace(requirements("Requirement after preference")));
        assertThat(preferenceFirst.getFirst().value()).isInstanceOf(PreferenceRepresentation.class);
        assertThat(preferenceFirst.getLast().value()).isInstanceOf(RequirementRepresentation.class);
        assertThat(planVersion()).isEqualTo(2);
        assertPreferenceSnapshot("create before requirement", 1);
        assertRequirementSnapshot("Requirement after preference");
    }

    @Test
    void requirementReplacementRacesPreferenceReplacementWithoutPartialChildren() throws Exception {
        planPreferenceService.create(preferenceCreate("original"));
        var outcomes = race(
                () -> requirementReplacementService.replace(requirements("Requirement before replacement")),
                () -> planPreferenceService.replace(preferenceReplacement("race replacement")));
        assertRequirementPreferenceRace(outcomes);
        assertThat(count("SELECT count(*) FROM planning_plan_preference WHERE plan_id = :planId", null)).isOne();
        assertPreferenceSnapshot("original", 1);
        assertRequirementSnapshot("Requirement before replacement");

        prepareDatabase();
        planPreferenceService.create(preferenceCreate("original"));
        var preferenceFirst = race(
                () -> planPreferenceService.replace(preferenceReplacement("replace before requirement")),
                () -> requirementReplacementService.replace(requirements("Requirement after replacement")));
        assertThat(preferenceFirst.getFirst().value()).isInstanceOf(PreferenceRepresentation.class);
        assertThat(preferenceFirst.getLast().value()).isInstanceOf(RequirementRepresentation.class);
        assertPreferenceSnapshot("replace before requirement", 2);
        assertRequirementSnapshot("Requirement after replacement");
    }

    @Test
    void membershipRemovalRacesPlanCreationAndPreferenceWrite() throws Exception {
        var planCreation = race(
                () -> planCreationService.create(planCreate("member-plan-race")),
                () -> { membershipExitService.remove(new RemoveGroupMemberCommand(ORGANIZER_ID, GROUP_ID, MEMBER_ID, "remove-plan-member", CORRELATION_ID)); return "removed"; });
        assertThat(planCreation.getFirst().value()).isInstanceOf(PlanRepresentation.class);
        assertThat(planCreation.getLast().value()).isEqualTo("removed");
        var createdPlanId = ((PlanRepresentation) planCreation.getFirst().value()).planId();
        assertCreatedPlanSnapshot(createdPlanId);
        assertRemovedMembership(2, "plan.created", 1);

        prepareDatabase();
        var removalBeforePlan = race(
                () -> { membershipExitService.remove(new RemoveGroupMemberCommand(ORGANIZER_ID, GROUP_ID, MEMBER_ID, "remove-before-plan", CORRELATION_ID)); return "removed"; },
                () -> planCreationService.create(planCreate("removed-member-plan")));
        assertThat(removalBeforePlan.getFirst().value()).isEqualTo("removed");
        assertPlanCreationFailure(removalBeforePlan.getLast().failure());
        assertThat(count("SELECT count(*) FROM planning_plan WHERE group_id = :groupId")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.create'")).isZero();
        assertRemovedMembership(2, "plan.created", 0);

        prepareDatabase();
        var preference = race(
                () -> planPreferenceService.create(preferenceCreate("membership race preference")),
                () -> { membershipExitService.remove(new RemoveGroupMemberCommand(ORGANIZER_ID, GROUP_ID, MEMBER_ID, "remove-preference-member", CORRELATION_ID)); return "removed"; });
        assertThat(preference.getFirst().value()).isInstanceOf(PreferenceRepresentation.class);
        assertThat(preference.getLast().value()).isEqualTo("removed");
        assertPreferenceSnapshot("membership race preference", 1);
        assertRemovedMembership(2, null, 0);

        prepareDatabase();
        var removalBeforePreference = race(
                () -> { membershipExitService.remove(new RemoveGroupMemberCommand(ORGANIZER_ID, GROUP_ID, MEMBER_ID, "remove-before-preference", CORRELATION_ID)); return "removed"; },
                () -> planPreferenceService.create(preferenceCreate("removed member preference")));
        assertThat(removalBeforePreference.getFirst().value()).isEqualTo("removed");
        assertPreferenceFailure(removalBeforePreference.getLast().failure(), PreferenceException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        assertThat(preferenceRows()).isZero();
        assertThat(count("SELECT count(*) FROM planning_preference_selected_window WHERE plan_id = :planId", null)).isZero();
        assertThat(count("SELECT count(*) FROM planning_preference_ranked_item WHERE plan_id = :planId", null)).isZero();
        assertRemovedMembership(2, null, 0);
    }

    @Test
    void twoRequirementWritersWithOnePlanEtagLeaveOneCompleteDraft() throws Exception {
        var outcomes = race(
                () -> requirementReplacementService.replace(requirements("First requirement writer")),
                () -> requirementReplacementService.replace(requirements("Second requirement writer")));
        assertThat(outcomes.getFirst().value()).isInstanceOf(RequirementRepresentation.class);
        assertRequirementFailure(outcomes.getLast().failure(), RequirementReplacementException.Reason.PRECONDITION_FAILED);
        assertThat(planVersion()).isEqualTo(2);
        assertRequirementSnapshot("First requirement writer");
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced'")).isOne();
    }

    @Test
    void twoPreferenceWritersWithOnePreferenceEtagLeaveOneCurrentPreference() throws Exception {
        planPreferenceService.create(preferenceCreate("original"));
        var outcomes = race(
                () -> planPreferenceService.replace(preferenceReplacement("first writer")),
                () -> planPreferenceService.replace(preferenceReplacement("second writer")));
        assertThat(outcomes.getFirst().value()).isInstanceOf(PreferenceRepresentation.class);
        assertPreferenceFailure(outcomes.getLast().failure(), PreferenceException.Reason.PRECONDITION_FAILED);
        assertThat(preferenceRows()).isOne();
        assertPreferenceSnapshot("first writer", 2);
    }

    @Test
    void cancellationRacesRequirementAndPreferenceWritesWithoutPartialData() throws Exception {
        var requirementRace = race(
                () -> planCancellationService.cancel(new CancelPlanCommand(ORGANIZER_ID, PLAN_ID, 1, "cancel-requirements", CORRELATION_ID)),
                () -> requirementReplacementService.replace(requirements("cancel race requirements")));
        assertThat(requirementRace.getFirst().value()).isInstanceOf(PlanRepresentation.class);
        assertRequirementFailure(requirementRace.getLast().failure(), RequirementReplacementException.Reason.PRECONDITION_FAILED);
        assertCancelledPlan();
        assertOriginalRequirementSnapshot();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced'")).isZero();

        prepareDatabase();
        var requirementFirst = race(
                () -> requirementReplacementService.replace(requirements("requirements before cancellation")),
                () -> planCancellationService.cancel(new CancelPlanCommand(ORGANIZER_ID, PLAN_ID, 1, "requirements-cancel", CORRELATION_ID)));
        assertThat(requirementFirst.getFirst().value()).isInstanceOf(RequirementRepresentation.class);
        assertCancellationFailure(requirementFirst.getLast().failure(), PlanCancellationException.Reason.PRECONDITION_FAILED);
        assertThat(planState()).isEqualTo("COLLABORATING");
        assertThat(planVersion()).isEqualTo(2);
        assertRequirementSnapshot("requirements before cancellation");
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.cancelled'")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.cancel'")).isZero();

        prepareDatabase();
        planPreferenceService.create(preferenceCreate("original"));
        var preferenceRace = race(
                () -> planCancellationService.cancel(new CancelPlanCommand(ORGANIZER_ID, PLAN_ID, 1, "cancel-preference", CORRELATION_ID)),
                () -> planPreferenceService.replace(preferenceReplacement("cancel race preference")));
        assertThat(preferenceRace.getFirst().value()).isInstanceOf(PlanRepresentation.class);
        assertPreferenceFailure(preferenceRace.getLast().failure(), PreferenceException.Reason.INVALID_PLAN_STATE);
        assertCancelledPlan();
        assertPreferenceSnapshot("original", 1);

        prepareDatabase();
        planPreferenceService.create(preferenceCreate("original"));
        var preferenceFirst = race(
                () -> planPreferenceService.replace(preferenceReplacement("preference before cancellation")),
                () -> planCancellationService.cancel(new CancelPlanCommand(ORGANIZER_ID, PLAN_ID, 1, "preference-cancel", CORRELATION_ID)));
        assertThat(preferenceFirst.getFirst().value()).isInstanceOf(PreferenceRepresentation.class);
        assertThat(preferenceFirst.getLast().value()).isInstanceOf(PlanRepresentation.class);
        assertCancelledPlan();
        assertPreferenceSnapshot("preference before cancellation", 2);
    }

    private InvitationRepresentation invite() {
        return invitationService.create(new CreateInvitationCommand(
                ORGANIZER_ID, GROUP_ID, OUTSIDER_ID, 24, "invite-" + UUID.randomUUID(), CORRELATION_ID));
    }

    private <T> List<ConcurrentDatabaseWorkers.Outcome<T>> race(Supplier<T> first, Supplier<T> second) throws Exception {
        return ConcurrentDatabaseWorkers.runOrdered(
                dataSource,
                transactionManager,
                "SELECT group_id FROM group_account WHERE group_id = ? FOR UPDATE",
                GROUP_ID,
                first,
                second);
    }

    private void assertAcceptanceRows(long acceptedAudits, long completedKeys) {
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :member AND status = 'ACTIVE'", OUTSIDER_ID)).isOne();
        assertThat(count("SELECT count(*) FROM group_invitation WHERE state = 'CONSUMED'")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.invitation.accepted'")).isEqualTo(acceptedAudits);
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.invites.accept' AND state = 'COMPLETED'")).isEqualTo(completedKeys);
        assertThat(groupVersion()).isEqualTo(2);
    }

    private void assertInvitationUnavailable(Throwable failure) {
        assertThat(failure).isInstanceOf(InvitationException.class);
        assertThat(((InvitationException) failure).reason()).isEqualTo(InvitationException.Reason.INVITATION_UNAVAILABLE);
    }

    private void assertRequirementFailure(Throwable failure, RequirementReplacementException.Reason reason) {
        assertThat(failure).isInstanceOf(RequirementReplacementException.class);
        assertThat(((RequirementReplacementException) failure).reason()).isEqualTo(reason);
    }

    private void assertPreferenceFailure(Throwable failure, PreferenceException.Reason reason) {
        assertThat(failure).isInstanceOf(PreferenceException.class);
        assertThat(((PreferenceException) failure).reason()).isEqualTo(reason);
    }

    private void assertCancellationFailure(Throwable failure, PlanCancellationException.Reason reason) {
        assertThat(failure).isInstanceOf(PlanCancellationException.class);
        assertThat(((PlanCancellationException) failure).reason()).isEqualTo(reason);
    }

    private void assertPlanCreationFailure(Throwable failure) {
        assertThat(failure).isInstanceOf(PlanCreationException.class);
        assertThat(((PlanCreationException) failure).reason()).isEqualTo(PlanCreationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
    }

    private void resetMembershipAndInvitationState() {
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM group_invitation").update();
        jdbcClient.sql("DELETE FROM group_membership WHERE group_id = :groupId AND account_id = :member")
                .param("groupId", GROUP_ID).param("member", OUTSIDER_ID).update();
        jdbcClient.sql("UPDATE group_account SET version = 1 WHERE group_id = :groupId").param("groupId", GROUP_ID).update();
    }

    private void assertRequirementPreferenceRace(List<? extends ConcurrentDatabaseWorkers.Outcome<?>> outcomes) {
        assertThat(outcomes.getFirst().succeeded()).isTrue();
        assertPreferenceFailure(outcomes.getLast().failure(), PreferenceException.Reason.REQUIREMENT_VERSION_CHANGED);
        assertThat(planVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM planning_requirement_draft WHERE plan_id = :planId", null)).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced'")).isOne();
    }

    private void assertRemovedMembership(long expectedGroupVersion, String writeAction, long expectedWriteAudits) {
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :member AND status = 'REMOVED'", MEMBER_ID)).isOne();
        assertThat(groupVersion()).isEqualTo(expectedGroupVersion);
        if (writeAction != null) {
            assertThat(count("SELECT count(*) FROM audit_event WHERE action = :action", null, writeAction)).isEqualTo(expectedWriteAudits);
        }
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'group.membership.removed'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'groups.members.remove' AND state = 'COMPLETED'")).isOne();
    }

    private void assertCreatedPlanSnapshot(UUID planId) {
        var plan = jdbcClient.sql("""
                        SELECT group_id, title, state, created_by_account_id, version
                        FROM planning_plan WHERE plan_id = :createdPlanId
                        """).param("createdPlanId", planId)
                .query((result, rowNumber) -> new PlanRow(
                        result.getObject("group_id", UUID.class), result.getString("title"), result.getString("state"),
                        result.getObject("created_by_account_id", UUID.class), result.getLong("version"))).single();
        assertThat(plan).isEqualTo(new PlanRow(GROUP_ID, "Member plan", "COLLABORATING", MEMBER_ID, 1));
        var requirement = jdbcClient.sql("""
                        SELECT category, time_zone, area_code, radius_km, minimum_headcount,
                               maximum_headcount, budget_currency, budget_minimum_minor_units,
                               budget_maximum_minor_units, provider_safe_notes, category_attributes::text
                        FROM planning_requirement_draft WHERE plan_id = :createdPlanId
                        """).param("createdPlanId", planId)
                .query((result, rowNumber) -> new RequirementRow(
                        result.getString("category"), result.getString("time_zone"), result.getString("area_code"),
                        result.getInt("radius_km"), result.getInt("minimum_headcount"), result.getInt("maximum_headcount"),
                        result.getString("budget_currency"), result.getObject("budget_minimum_minor_units", Long.class),
                        result.getObject("budget_maximum_minor_units", Long.class), result.getString("provider_safe_notes"),
                        result.getString("category_attributes"))).single();
        assertThat(requirement).isEqualTo(new RequirementRow(
                "COURT", "Asia/Manila", "BGC", 5, 4, 10, null, null, null, null, "{}"));
        var window = jdbcClient.sql("""
                        SELECT sort_order, starts_at, ends_at, retired_at
                        FROM planning_candidate_window WHERE plan_id = :createdPlanId
                        """).param("createdPlanId", planId)
                .query((result, rowNumber) -> new WindowRow(
                        result.getInt("sort_order"), result.getObject("starts_at", OffsetDateTime.class),
                        result.getObject("ends_at", OffsetDateTime.class), result.getObject("retired_at", OffsetDateTime.class))).single();
        assertThat(window).isEqualTo(new WindowRow(
                1, OffsetDateTime.parse("2027-01-10T09:00:00Z"), OffsetDateTime.parse("2027-01-10T11:00:00Z"), null));
        assertThat(jdbcClient.sql("SELECT must_have FROM planning_requirement_must_have WHERE plan_id = :createdPlanId")
                .param("createdPlanId", planId).query(String.class).single()).isEqualTo("parking");
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.create' AND state = 'COMPLETED'")).isOne();
    }

    private void assertCancelledPlan() {
        assertThat(planState()).isEqualTo("CANCELLED");
        assertThat(planVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.cancelled'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.cancel' AND state = 'COMPLETED'")).isOne();
    }

    private void insertPlan() {
        jdbcClient.sql("""
                INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version)
                VALUES (:planId, :groupId, 'Race plan', 'COLLABORATING', :organizer, 1)
                """).param("planId", PLAN_ID).param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        jdbcClient.sql("""
                INSERT INTO planning_requirement_draft (plan_id, category, time_zone, area_code, radius_km, minimum_headcount, maximum_headcount, category_attributes)
                VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, '{}'::jsonb)
                """).param("planId", PLAN_ID).update();
        jdbcClient.sql("""
                INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at)
                VALUES (:windowId, :planId, 1, '2027-01-09T09:00:00Z', '2027-01-09T11:00:00Z')
                """).param("windowId", WINDOW_ID).param("planId", PLAN_ID).update();
    }

    private void membership(UUID accountId, String role) {
        jdbcClient.sql("""
                INSERT INTO group_membership (group_id, account_id, role, status, joined_at)
                VALUES (:groupId, :accountId, :role, 'ACTIVE', statement_timestamp())
                """).param("groupId", GROUP_ID).param("accountId", accountId).param("role", role).update();
    }

    private ReplaceRequirementsCommand requirements(String title) {
        var startsAt = title.startsWith("First")
                ? OffsetDateTime.parse("2027-01-09T09:15:00Z")
                : OffsetDateTime.parse("2027-01-09T09:30:00Z");
        return new ReplaceRequirementsCommand(ORGANIZER_ID, PLAN_ID, 1,
                new RequirementReplacementRequest(title, ActivityCategory.COURT, "Asia/Manila",
                        List.of(new RequirementReplacementRequest.CandidateWindowRequest(WINDOW_ID,
                                startsAt, startsAt.plusHours(2))),
                        new CreatePlanRequest.AreaRequest("BGC", 5), new CreatePlanRequest.HeadcountRequest(4, 10),
                        null, List.of(title), title + " notes", Map.of()), CORRELATION_ID);
    }

    private CreatePreferenceCommand preferenceCreate(String note) {
        return new CreatePreferenceCommand(MEMBER_ID, PLAN_ID, preferenceRequest(note));
    }

    private ReplacePreferenceCommand preferenceReplacement(String note) {
        return new ReplacePreferenceCommand(MEMBER_ID, PLAN_ID, 1, preferenceRequest(note));
    }

    private CreatePreferenceRequest preferenceRequest(String note) {
        return new CreatePreferenceRequest(1L, Attendance.JOINING, 0, List.of(WINDOW_ID), null, List.of(note), note);
    }

    private CreatePlanCommand planCreate(String key) {
        return new CreatePlanCommand(MEMBER_ID, GROUP_ID, key,
                new CreatePlanRequest("Member plan", ActivityCategory.COURT, "Asia/Manila",
                        List.of(new CreatePlanRequest.CandidateWindowRequest(OffsetDateTime.parse("2027-01-10T09:00:00Z"), OffsetDateTime.parse("2027-01-10T11:00:00Z"))),
                        new CreatePlanRequest.AreaRequest("BGC", 5), new CreatePlanRequest.HeadcountRequest(4, 10),
                        null, List.of("parking"), null, Map.of()), CORRELATION_ID);
    }

    private long count(String sql) {
        return count(sql, null);
    }

    private long count(String sql, UUID memberId) {
        var statement = jdbcClient.sql(sql).param("groupId", GROUP_ID).param("planId", PLAN_ID)
                .param("member", memberId == null ? MEMBER_ID : memberId).param("organizer", ORGANIZER_ID);
        return statement.query(Long.class).single();
    }

    private long count(String sql, UUID ignored, String action) {
        return jdbcClient.sql(sql).param("action", action).query(Long.class).single();
    }

    private long planVersion() {
        return jdbcClient.sql("SELECT version FROM planning_plan WHERE plan_id = :planId").param("planId", PLAN_ID).query(Long.class).single();
    }

    private long groupVersion() {
        return jdbcClient.sql("SELECT version FROM group_account WHERE group_id = :groupId")
                .param("groupId", GROUP_ID).query(Long.class).single();
    }

    private String planState() {
        return jdbcClient.sql("SELECT state FROM planning_plan WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(String.class).single();
    }

    private long preferenceRows() {
        return count("SELECT count(*) FROM planning_plan_preference WHERE plan_id = :planId", null);
    }

    private long preferenceVersion() {
        return jdbcClient.sql("SELECT version FROM planning_plan_preference WHERE plan_id = :planId AND account_id = :member")
                .param("planId", PLAN_ID).param("member", MEMBER_ID).query(Long.class).single();
    }

    private void assertRequirementSnapshot(String title) {
        assertThat(jdbcClient.sql("SELECT title FROM planning_plan WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(String.class).single()).isEqualTo(title);
        assertThat(jdbcClient.sql("SELECT provider_safe_notes FROM planning_requirement_draft WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(String.class).single()).isEqualTo(title + " notes");
        assertThat(jdbcClient.sql("""
                        SELECT category, time_zone, area_code, radius_km, minimum_headcount,
                               maximum_headcount, budget_currency, budget_minimum_minor_units,
                               budget_maximum_minor_units, provider_safe_notes, category_attributes::text
                        FROM planning_requirement_draft WHERE plan_id = :planId
                        """).param("planId", PLAN_ID)
                .query((result, rowNumber) -> new RequirementRow(
                        result.getString("category"), result.getString("time_zone"), result.getString("area_code"),
                        result.getInt("radius_km"), result.getInt("minimum_headcount"), result.getInt("maximum_headcount"),
                        result.getString("budget_currency"), result.getObject("budget_minimum_minor_units", Long.class),
                        result.getObject("budget_maximum_minor_units", Long.class), result.getString("provider_safe_notes"),
                        result.getString("category_attributes"))).single())
                .isEqualTo(new RequirementRow(
                        "COURT", "Asia/Manila", "BGC", 5, 4, 10, null, null, null, title + " notes", "{}"));
        assertThat(count("SELECT count(*) FROM planning_requirement_must_have WHERE plan_id = :planId", null)).isOne();
        assertThat(jdbcClient.sql("SELECT must_have FROM planning_requirement_must_have WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(String.class).single()).isEqualTo(title);
        var expectedStart = title.startsWith("First")
                ? OffsetDateTime.parse("2027-01-09T09:15:00Z")
                : OffsetDateTime.parse("2027-01-09T09:30:00Z");
        assertThat(jdbcClient.sql("SELECT starts_at FROM planning_candidate_window WHERE candidate_window_id = :windowId")
                .param("windowId", WINDOW_ID).query(OffsetDateTime.class).single()).isEqualTo(expectedStart);
        assertThat(jdbcClient.sql("SELECT ends_at FROM planning_candidate_window WHERE candidate_window_id = :windowId")
                .param("windowId", WINDOW_ID).query(OffsetDateTime.class).single()).isEqualTo(expectedStart.plusHours(2));
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE plan_id = :planId AND retired_at IS NULL", null)).isOne();
    }

    private void assertOriginalRequirementSnapshot() {
        var plan = jdbcClient.sql("""
                        SELECT group_id, title, state, created_by_account_id, version
                        FROM planning_plan WHERE plan_id = :planId
                        """).param("planId", PLAN_ID)
                .query((result, rowNumber) -> new PlanRow(
                        result.getObject("group_id", UUID.class), result.getString("title"), result.getString("state"),
                        result.getObject("created_by_account_id", UUID.class), result.getLong("version"))).single();
        assertThat(plan).isEqualTo(new PlanRow(GROUP_ID, "Race plan", planState(), ORGANIZER_ID, planVersion()));
        var requirement = jdbcClient.sql("""
                        SELECT category, time_zone, area_code, radius_km, minimum_headcount,
                               maximum_headcount, budget_currency, budget_minimum_minor_units,
                               budget_maximum_minor_units, provider_safe_notes, category_attributes::text
                        FROM planning_requirement_draft WHERE plan_id = :planId
                        """).param("planId", PLAN_ID)
                .query((result, rowNumber) -> new RequirementRow(
                        result.getString("category"), result.getString("time_zone"), result.getString("area_code"),
                        result.getInt("radius_km"), result.getInt("minimum_headcount"), result.getInt("maximum_headcount"),
                        result.getString("budget_currency"), result.getObject("budget_minimum_minor_units", Long.class),
                        result.getObject("budget_maximum_minor_units", Long.class), result.getString("provider_safe_notes"),
                        result.getString("category_attributes"))).single();
        assertThat(requirement).isEqualTo(new RequirementRow(
                "COURT", "Asia/Manila", "BGC", 5, 4, 10, null, null, null, null, "{}"));
        assertThat(count("SELECT count(*) FROM planning_requirement_must_have WHERE plan_id = :planId", null)).isZero();
        var window = jdbcClient.sql("""
                        SELECT sort_order, starts_at, ends_at, retired_at
                        FROM planning_candidate_window WHERE candidate_window_id = :windowId
                        """).param("windowId", WINDOW_ID)
                .query((result, rowNumber) -> new WindowRow(
                        result.getInt("sort_order"), result.getObject("starts_at", OffsetDateTime.class),
                        result.getObject("ends_at", OffsetDateTime.class), result.getObject("retired_at", OffsetDateTime.class))).single();
        assertThat(window).isEqualTo(new WindowRow(
                1, OffsetDateTime.parse("2027-01-09T09:00:00Z"), OffsetDateTime.parse("2027-01-09T11:00:00Z"), null));
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE plan_id = :planId", null)).isOne();
    }

    private void assertPreferenceSnapshot(String note, long version) {
        var row = jdbcClient.sql("""
                        SELECT private_note, basis_plan_version, attendance, guest_count,
                               personal_budget_currency, version
                        FROM planning_plan_preference
                        WHERE plan_id = :planId AND account_id = :member
                        """).param("planId", PLAN_ID).param("member", MEMBER_ID)
                .query((result, rowNumber) -> new PreferenceRow(
                        result.getString("private_note"),
                        result.getLong("basis_plan_version"),
                        result.getString("attendance"),
                        result.getInt("guest_count"),
                        result.getString("personal_budget_currency"),
                        result.getLong("version"))).single();
        assertThat(row).isEqualTo(new PreferenceRow(note, 1L, "JOINING", 0, null, version));
        assertThat(jdbcClient.sql("SELECT preference_text FROM planning_preference_ranked_item WHERE plan_id = :planId AND account_id = :member")
                .param("planId", PLAN_ID).param("member", MEMBER_ID).query(String.class).single()).isEqualTo(note);
        assertThat(jdbcClient.sql("SELECT candidate_window_id FROM planning_preference_selected_window WHERE plan_id = :planId AND account_id = :member")
                .param("planId", PLAN_ID).param("member", MEMBER_ID).query(UUID.class).single()).isEqualTo(WINDOW_ID);
        assertThat(count("SELECT count(*) FROM planning_preference_ranked_item WHERE plan_id = :planId", null)).isOne();
        assertThat(count("SELECT count(*) FROM planning_preference_selected_window WHERE plan_id = :planId", null)).isOne();
    }

    private record PreferenceRow(
            String note,
            long basisPlanVersion,
            String attendance,
            int guestCount,
            String personalBudgetCurrency,
            long version) {
    }

    private record RequirementRow(
            String category,
            String timeZone,
            String areaCode,
            int radiusKm,
            int minimumHeadcount,
            int maximumHeadcount,
            String budgetCurrency,
            Long budgetMinimumMinorUnits,
            Long budgetMaximumMinorUnits,
            String providerSafeNotes,
            String categoryAttributes) {
    }

    private record PlanRow(UUID groupId, String title, String state, UUID createdByAccountId, long version) {
    }

    private record WindowRow(int sortOrder, OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime retiredAt) {
    }
}
