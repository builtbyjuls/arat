package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.MembershipExitException;
import com.builtbyjuls.arat.groups.api.RemoveGroupMemberCommand;
import com.builtbyjuls.arat.groups.application.MembershipExitService;
import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.groups.infrastructure.GroupRepository;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.planning.api.CreatePreferenceCommand;
import com.builtbyjuls.arat.planning.api.CreatePreferenceRequest;
import com.builtbyjuls.arat.planning.api.PreferenceException;
import com.builtbyjuls.arat.planning.api.PreferenceRepresentation;
import com.builtbyjuls.arat.planning.api.ReplacePreferenceCommand;
import com.builtbyjuls.arat.planning.api.ReplaceRequirementsCommand;
import com.builtbyjuls.arat.planning.api.RequirementReplacementException;
import com.builtbyjuls.arat.planning.api.RequirementReplacementRequest;
import com.builtbyjuls.arat.planning.api.RequirementRepresentation;
import com.builtbyjuls.arat.planning.domain.Attendance;
import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanPreferenceIT extends PostgreSqlIntegrationTest {

    private static final UUID ORGANIZER_ID = UUID.fromString("84000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("84000000-0000-4000-8000-000000000002");
    private static final UUID INACTIVE_ID = UUID.fromString("84000000-0000-4000-8000-000000000003");
    private static final UUID OUTSIDER_ID = UUID.fromString("84000000-0000-4000-8000-000000000004");
    private static final UUID GROUP_ID = UUID.fromString("85000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("86000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_PLAN_ID = UUID.fromString("86000000-0000-4000-8000-000000000002");
    private static final UUID WINDOW_ID = UUID.fromString("87000000-0000-4000-8000-000000000001");
    private static final UUID RETIRED_WINDOW_ID = UUID.fromString("87000000-0000-4000-8000-000000000002");
    private static final UUID SECOND_WINDOW_ID = UUID.fromString("87000000-0000-4000-8000-000000000003");

    @Autowired private WebApplicationContext context;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private PlanPreferenceService preferenceService;
    @Autowired private RequirementReplacementService requirementReplacementService;
    @Autowired private MembershipExitService membershipExitService;
    @MockitoSpyBean private GroupMembershipAccess groupMembershipAccess;
    @MockitoSpyBean private GroupRepository groupRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM planning_preference_ranked_item").update();
        jdbcClient.sql("DELETE FROM planning_preference_selected_window").update();
        jdbcClient.sql("DELETE FROM planning_plan_preference").update();
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:organizer, :member, :inactive, :outsider)")
                .param("organizer", ORGANIZER_ID).param("member", MEMBER_ID).param("inactive", INACTIVE_ID).param("outsider", OUTSIDER_ID).update();
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:organizer, 'Preference organizer'), (:member, 'Preference member'), (:inactive, 'Inactive member'), (:outsider, 'Preference outsider')")
                .param("organizer", ORGANIZER_ID).param("member", MEMBER_ID).param("inactive", INACTIVE_ID).param("outsider", OUTSIDER_ID).update();
        jdbcClient.sql("INSERT INTO group_account (group_id, name, description, status, created_by_account_id) VALUES (:groupId, 'Preference group', '', 'ACTIVE', :organizer)")
                .param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        jdbcClient.sql("INSERT INTO group_membership (group_id, account_id, role, status, joined_at, ended_at) VALUES (:groupId, :organizer, 'ORGANIZER', 'ACTIVE', statement_timestamp(), NULL), (:groupId, :member, 'MEMBER', 'ACTIVE', statement_timestamp(), NULL), (:groupId, :inactive, 'MEMBER', 'LEFT', statement_timestamp(), statement_timestamp())")
                .param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).param("member", MEMBER_ID).param("inactive", INACTIVE_ID).update();
        insertPlan(PLAN_ID, WINDOW_ID, RETIRED_WINDOW_ID);
        insertPlan(SECOND_PLAN_ID, SECOND_WINDOW_ID, null);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(correlationIdFilter).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void createsAndReadsGroupVisiblePreferencesWithoutChangingPlanEtag() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", "/api/v1/plans/" + PLAN_ID + "/members/me/preference"))
                .andExpect(jsonPath("$.accountId").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.basisPlanVersion").value(1))
                .andExpect(jsonPath("$.personalBudget.amount").value("500.00"));
        getAs(MEMBER_ID, "/api/v1/plans/{planId}/members/me/preference", PLAN_ID)
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        getAs(ORGANIZER_ID, "/api/v1/plans/{planId}/preferences", PLAN_ID)
                .andExpect(status().isOk()).andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.items[0].current").value(true))
                .andExpect(jsonPath("$.items[0].preference.privateNote").value("I can bring equipment."));
        getAs(MEMBER_ID, "/api/v1/plans/{planId}", PLAN_ID)
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        assertThat(count("SELECT count(*) FROM planning_plan_preference")).isOne();
        assertThat(count("SELECT count(*) FROM planning_preference_selected_window")).isOne();
        assertThat(count("SELECT count(*) FROM planning_preference_ranked_item")).isEqualTo(2);
    }

    @Test
    void preservesAnOmittedPersonalBudgetAsAbsent() throws Exception {
        var withoutBudget = preference(WINDOW_ID).replace("\"personalBudget\":{\"currency\":\"PHP\",\"amount\":\"500.00\"},", "");
        putAs(MEMBER_ID, PLAN_ID, "*", withoutBudget).andExpect(status().isCreated()).andExpect(jsonPath("$.personalBudget").doesNotExist());
        getAs(MEMBER_ID, "/api/v1/plans/{planId}/members/me/preference", PLAN_ID)
                .andExpect(status().isOk()).andExpect(jsonPath("$.personalBudget").doesNotExist());
    }

    @Test
    void validatesPreferenceBoundsAndAttendanceStates() throws Exception {
        for (var attendance : Attendance.values()) {
            var member = UUID.randomUUID();
            jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:id, 'Preference test member')").param("id", member).update();
            jdbcClient.sql("INSERT INTO group_membership (group_id, account_id, role, status, joined_at) VALUES (:groupId, :id, 'MEMBER', 'ACTIVE', statement_timestamp())").param("groupId", GROUP_ID).param("id", member).update();
            var body = preference(WINDOW_ID).replace("\"JOINING\"", "\"" + attendance.name() + "\"");
            if (attendance == Attendance.NOT_JOINING) {
                body = body.replace("\"guestCount\":1", "\"guestCount\":0");
            }
            putAs(member, PLAN_ID, "*", body).andExpect(status().isCreated());
        }
        for (var body : List.of(
                preference(WINDOW_ID).replace("\"basisPlanVersion\":1", "\"basisPlanVersion\":0"),
                preference(WINDOW_ID).replace("\"guestCount\":1", "\"guestCount\":-1"),
                preference(WINDOW_ID).replace("\"guestCount\":1", "\"guestCount\":21"),
                preference(WINDOW_ID).replace("\"JOINING\"", "\"NOT_JOINING\""),
                preference(WINDOW_ID).replace("\"selectedWindowIds\":[\"" + WINDOW_ID + "\"]", "\"selectedWindowIds\":[\"" + WINDOW_ID + "\",\"" + WINDOW_ID + "\"]"),
                preference(WINDOW_ID).replace("\"amount\":\"500.00\"", "\"amount\":\"1000000.01\""),
                preference(WINDOW_ID).replace("\"currency\":\"PHP\"", "\"currency\":\"USD\""),
                preference(WINDOW_ID).replace("\"rankedPreferences\":[\"indoor court\",\"parking\"]", "\"rankedPreferences\":[\"indoor court\",\"indoor court\"]"),
                preference(WINDOW_ID).replace("\"rankedPreferences\":[\"indoor court\",\"parking\"]", "\"rankedPreferences\":" + rankedPreferences(11, 2)),
                preference(WINDOW_ID).replace("indoor court", "x".repeat(81)),
                preference(WINDOW_ID).replace("\"privateNote\":\"I can bring equipment.\"", "\"privateNote\":\"" + "x".repeat(1001) + "\""))) {
            var response = putAs(ORGANIZER_ID, PLAN_ID, "*", body).andReturn().getResponse();
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        }
        var zeroGuest = member();
        putAs(zeroGuest, PLAN_ID, "*", preference(WINDOW_ID).replace("\"guestCount\":1", "\"guestCount\":0")).andExpect(status().isCreated());
        var maxGuest = member();
        putAs(maxGuest, PLAN_ID, "*", preference(WINDOW_ID).replace("\"guestCount\":1", "\"guestCount\":20")).andExpect(status().isCreated());
        var tenRanks = member();
        putAs(tenRanks, PLAN_ID, "*", preference(WINDOW_ID).replace("\"rankedPreferences\":[\"indoor court\",\"parking\"]", "\"rankedPreferences\":" + rankedPreferences(10, 80))).andExpect(status().isCreated());
    }

    @Test
    void rejectsMissingWrongDuplicateAndInvalidWritesWithoutMutation() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, null, preference(WINDOW_ID)).andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        putAs(MEMBER_ID, PLAN_ID, "\"1\"", preference(WINDOW_ID)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRECONDITION"));
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(count("SELECT count(*) FROM planning_plan_preference")).isOne();
    }

    @Test
    void replacesEveryPreferenceFieldWithItsEtagWithoutChangingThePlanEtag() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        var replacement = preference(WINDOW_ID)
                .replace("\"attendance\":\"JOINING\"", "\"attendance\":\"AVAILABLE\"")
                .replace("\"guestCount\":1", "\"guestCount\":2")
                .replace("\"amount\":\"500.00\"", "\"amount\":\"750.00\"")
                .replace("[\"indoor court\",\"parking\"]", "[\"air conditioning\"]")
                .replace("I can bring equipment.", "Updated preference.");

        putAs(MEMBER_ID, PLAN_ID, null, "\"1\"", replacement)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.attendance").value("AVAILABLE"))
                .andExpect(jsonPath("$.guestCount").value(2))
                .andExpect(jsonPath("$.personalBudget.amount").value("750.00"))
                .andExpect(jsonPath("$.rankedPreferences[0]").value("air conditioning"))
                .andExpect(jsonPath("$.privateNote").value("Updated preference."));
        getAs(MEMBER_ID, "/api/v1/plans/{planId}/members/me/preference", PLAN_ID)
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
        getAs(MEMBER_ID, "/api/v1/plans/{planId}", PLAN_ID)
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        assertThat(count("SELECT count(*) FROM planning_preference_ranked_item")).isOne();
    }

    @Test
    void rejectsMissingMalformedDualStaleAndWrongResourceReplacementPreconditions() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, null, null, preference(WINDOW_ID))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        putAs(MEMBER_ID, PLAN_ID, null, "1", preference(WINDOW_ID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRECONDITION"));
        putAs(MEMBER_ID, PLAN_ID, "*", "\"1\"", preference(WINDOW_ID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRECONDITION"));
        putAs(MEMBER_ID, PLAN_ID, null, "\"1\"", preference(WINDOW_ID))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PREFERENCE_NOT_FOUND"));
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        putAs(MEMBER_ID, PLAN_ID, null, "\"2\"", preference(WINDOW_ID))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(count("SELECT count(*) FROM planning_plan_preference")).isOne();
    }

    @Test
    void rejectsRetiredUnknownCrossPlanStaleCancelledAndNonMemberAccess() throws Exception {
        for (var window : List.of(RETIRED_WINDOW_ID, SECOND_WINDOW_ID, UUID.randomUUID())) {
            putAs(MEMBER_ID, PLAN_ID, "*", preference(window)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        putAs(OUTSIDER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isNotFound());
        putAs(INACTIVE_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isNotFound());
        jdbcClient.sql("UPDATE planning_plan SET version = 2 WHERE plan_id = :planId").param("planId", PLAN_ID).update();
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUIREMENT_VERSION_CHANGED"));
        jdbcClient.sql("UPDATE planning_plan SET state = 'CANCELLED', version = 1 WHERE plan_id = :planId").param("planId", PLAN_ID).update();
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PLAN_STATE"));
        getAs(OUTSIDER_ID, "/api/v1/plans/{planId}/preferences", PLAN_ID).andExpect(status().isNotFound());
    }

    @Test
    void reportsMissingOwnPreferenceAndMarksAcceptedPreferenceStaleAfterRequirementVersionAdvance() throws Exception {
        getAs(MEMBER_ID, "/api/v1/plans/{planId}/members/me/preference", PLAN_ID).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PREFERENCE_NOT_FOUND"));
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        jdbcClient.sql("UPDATE planning_plan SET version = version + 1 WHERE plan_id = :planId").param("planId", PLAN_ID).update();
        getAs(ORGANIZER_ID, "/api/v1/plans/{planId}/preferences", PLAN_ID)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].current").value(false));
    }

    @Test
    void requirementReplacementRejectsAnOldBasisWhenItChangesRetainedWindowTimes() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        replaceRequirements(WINDOW_ID, UUID.randomUUID())
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUIREMENT_VERSION_CHANGED"));
        assertThat(count("SELECT count(*) FROM planning_plan_preference")).isOne();
    }

    @Test
    void requirementReplacementRetiresSelectedWindowsWithoutDeletingPreferences() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        replaceRequirements(UUID.randomUUID())
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM planning_plan_preference")).isOne();
        assertThat(count("SELECT count(*) FROM planning_preference_selected_window WHERE candidate_window_id = :windowId", WINDOW_ID)).isOne();
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE candidate_window_id = :windowId AND retired_at IS NOT NULL", WINDOW_ID)).isOne();
    }

    @Test
    void databaseRejectsAnEleventhRankedPreference() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbcClient.sql("INSERT INTO planning_preference_ranked_item (plan_id, account_id, preference_text, sort_order) VALUES (:planId, :accountId, 'eleventh', 11)")
                .param("planId", PLAN_ID).param("accountId", MEMBER_ID).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void concurrentFirstWritesHaveOneWinnerAndRollbackLeavesNoPreference() throws Exception {
        var barrier = new CyclicBarrier(2);
        var command = command(MEMBER_ID);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = List.of(executor.submit(() -> createAfterBarrier(command, barrier)), executor.submit(() -> createAfterBarrier(command, barrier)));
            var results = List.of(outcomes.getFirst().get(10, TimeUnit.SECONDS), outcomes.getLast().get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(PreferenceRepresentation.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(PreferenceException.class::isInstance).map(PreferenceException.class::cast).map(PreferenceException::reason))
                    .containsExactly(PreferenceException.Reason.PRECONDITION_FAILED);
        }
        assertThat(count("SELECT count(*) FROM planning_plan_preference")).isOne();
        jdbcClient.sql("DELETE FROM planning_preference_ranked_item").update();
        jdbcClient.sql("DELETE FROM planning_preference_selected_window").update();
        jdbcClient.sql("DELETE FROM planning_plan_preference").update();
        jdbcClient.sql("CREATE FUNCTION fail_preference_window_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced preference failure'; END; $$").update();
        jdbcClient.sql("CREATE TRIGGER fail_preference_window_insert BEFORE INSERT ON planning_preference_selected_window FOR EACH ROW EXECUTE FUNCTION fail_preference_window_insert()").update();
        try {
            putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isInternalServerError());
            assertThat(count("SELECT count(*) FROM planning_plan_preference")).isZero();
        } finally {
            jdbcClient.sql("DROP TRIGGER IF EXISTS fail_preference_window_insert ON planning_preference_selected_window").update();
            jdbcClient.sql("DROP FUNCTION IF EXISTS fail_preference_window_insert()").update();
        }
    }

    @Test
    void concurrentReplacementsWithOneEtagHaveOneWinner() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        var barrier = new CyclicBarrier(2);
        var command = replaceCommand(MEMBER_ID, "Concurrent preference.");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = List.of(
                    executor.submit(() -> replaceAfterBarrier(command, barrier)),
                    executor.submit(() -> replaceAfterBarrier(command, barrier)));
            var results = List.of(outcomes.getFirst().get(10, TimeUnit.SECONDS), outcomes.getLast().get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(PreferenceRepresentation.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(PreferenceException.class::isInstance)
                    .map(PreferenceException.class::cast).map(PreferenceException::reason))
                    .containsExactly(PreferenceException.Reason.PRECONDITION_FAILED);
        }
        assertThat(count("SELECT version FROM planning_plan_preference WHERE plan_id = :windowId", PLAN_ID)).isEqualTo(2);
        assertThat(count("SELECT version FROM planning_plan WHERE plan_id = :windowId", PLAN_ID)).isOne();
    }

    @Test
    void preferenceReplacementBeforeRequirementReplacementPreservesItsOldBasis() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        var preferenceGroupLocked = new CountDownLatch(1);
        var allowPreference = new CountDownLatch(1);
        pausePreferenceAfterGroupLock(preferenceGroupLocked, allowPreference);
        try (var preferenceExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("preference-replacement").factory());
                var requirementExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("requirement-replacement").factory())) {
            var preference = preferenceExecutor.submit(() -> preferenceService.replace(replaceCommand(MEMBER_ID, "Concurrent basis preference.")));
            await(preferenceGroupLocked);
            var requirement = requirementExecutor.submit(() -> requirementReplacementService.replace(requirementReplacementCommand()));
            allowPreference.countDown();
            assertThat(preference.get(10, TimeUnit.SECONDS).version()).isEqualTo(2);
            assertThat(requirement.get(10, TimeUnit.SECONDS).title()).isEqualTo("Changed preference plan");
        }
        assertThat(count("SELECT version FROM planning_plan WHERE plan_id = :windowId", PLAN_ID)).isEqualTo(2);
        assertThat(count("SELECT basis_plan_version FROM planning_plan_preference WHERE plan_id = :windowId", PLAN_ID)).isOne();
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM planning_preference_selected_window
                        WHERE plan_id = :planId
                          AND candidate_window_id = :candidateWindowId
                        """)
                .param("planId", PLAN_ID)
                .param("candidateWindowId", WINDOW_ID)
                .query(Long.class)
                .single()).isOne();
        getAs(ORGANIZER_ID, "/api/v1/plans/{planId}/preferences", PLAN_ID)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].current").value(false));
    }

    @Test
    void requirementReplacementBeforePreferenceReplacementRejectsTheStaleBasis() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        var requirementGroupLocked = new CountDownLatch(1);
        var allowRequirement = new CountDownLatch(1);
        pauseRequirementAfterGroupLock(requirementGroupLocked, allowRequirement);
        try (var requirementExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("requirement-replacement").factory());
                var preferenceExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("preference-replacement").factory())) {
            var requirement = requirementExecutor.submit(() -> requirementReplacementService.replace(requirementReplacementCommand()));
            await(requirementGroupLocked);
            var preference = preferenceExecutor.submit(() -> replacePreferenceOrReturnFailure(replaceCommand(MEMBER_ID, "Concurrent basis preference.")));
            allowRequirement.countDown();
            assertThat(requirement.get(10, TimeUnit.SECONDS).title()).isEqualTo("Changed preference plan");
            assertThat(preference.get(10, TimeUnit.SECONDS)).isInstanceOf(PreferenceException.class)
                    .extracting(PreferenceException.class::cast)
                    .extracting(PreferenceException::reason)
                    .isEqualTo(PreferenceException.Reason.REQUIREMENT_VERSION_CHANGED);
        }
        assertThat(count("SELECT version FROM planning_plan WHERE plan_id = :windowId", PLAN_ID)).isEqualTo(2);
        assertThat(count("SELECT version FROM planning_plan_preference WHERE plan_id = :windowId", PLAN_ID)).isOne();
    }

    @Test
    void preferenceReplacementBeforeMembershipRemovalCommitsEveryReplacementField() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        var preferenceGroupLocked = new CountDownLatch(1);
        var allowPreference = new CountDownLatch(1);
        pausePreferenceAfterGroupLock(preferenceGroupLocked, allowPreference);
        try (var preferenceExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("preference-replacement").factory());
                var removalExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("membership-removal").factory())) {
            var preference = preferenceExecutor.submit(() -> preferenceService.replace(replaceCommand(MEMBER_ID, "Membership race preference.")));
            await(preferenceGroupLocked);
            var removal = removalExecutor.submit(this::removeMember);
            allowPreference.countDown();
            assertThat(preference.get(10, TimeUnit.SECONDS).version()).isEqualTo(2);
            assertThat(removal.get(10, TimeUnit.SECONDS)).isNotBlank();
        }
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :windowId AND account_id = :accountId AND status = 'REMOVED'", GROUP_ID, MEMBER_ID)).isOne();
        assertThat(count("SELECT version FROM planning_plan_preference WHERE plan_id = :windowId", PLAN_ID)).isEqualTo(2);
        assertThat(count("SELECT guest_count FROM planning_plan_preference WHERE plan_id = :windowId", PLAN_ID)).isEqualTo(2);
        assertThat(count("SELECT personal_budget_minor_units FROM planning_plan_preference WHERE plan_id = :windowId", PLAN_ID)).isEqualTo(75000);
        assertThat(count("SELECT count(*) FROM planning_preference_ranked_item WHERE plan_id = :windowId", PLAN_ID)).isOne();
    }

    @Test
    void membershipRemovalBeforePreferenceReplacementRejectsWithoutChangingThePreference() throws Exception {
        putAs(MEMBER_ID, PLAN_ID, "*", preference(WINDOW_ID)).andExpect(status().isCreated());
        var removalGroupLocked = new CountDownLatch(1);
        var allowRemoval = new CountDownLatch(1);
        pauseRemovalAfterGroupLock(removalGroupLocked, allowRemoval);
        try (var removalExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("membership-removal").factory());
                var preferenceExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("preference-replacement").factory())) {
            var removal = removalExecutor.submit(this::removeMember);
            await(removalGroupLocked);
            var preference = preferenceExecutor.submit(() -> replacePreferenceOrReturnFailure(replaceCommand(MEMBER_ID, "Membership race preference.")));
            allowRemoval.countDown();
            assertThat(removal.get(10, TimeUnit.SECONDS)).isNotBlank();
            assertThat(preference.get(10, TimeUnit.SECONDS)).isInstanceOf(PreferenceException.class)
                    .extracting(PreferenceException.class::cast)
                    .extracting(PreferenceException::reason)
                    .isEqualTo(PreferenceException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        assertThat(count("SELECT count(*) FROM group_membership WHERE group_id = :windowId AND account_id = :accountId AND status = 'REMOVED'", GROUP_ID, MEMBER_ID)).isOne();
        assertThat(count("SELECT version FROM planning_plan_preference WHERE plan_id = :windowId", PLAN_ID)).isOne();
        assertThat(count("SELECT guest_count FROM planning_plan_preference WHERE plan_id = :windowId", PLAN_ID)).isOne();
        assertThat(count("SELECT personal_budget_minor_units FROM planning_plan_preference WHERE plan_id = :windowId", PLAN_ID)).isEqualTo(50000);
        assertThat(count("SELECT count(*) FROM planning_preference_ranked_item WHERE plan_id = :windowId", PLAN_ID)).isEqualTo(2);
    }

    @Test
    void publishesPreferenceContractInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/members/me/preference'].put.operationId").value("putMemberPreference"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/members/me/preference'].put.parameters[?(@.name == 'If-None-Match')]").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/members/me/preference'].put.parameters[?(@.name == 'If-Match')]").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/members/me/preference'].put.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/members/me/preference'].put.responses['412']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/members/me/preference'].put.responses['428']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/preferences'].get.operationId").value("listPlanPreferences"))
                .andExpect(jsonPath("$.components.schemas.CreatePreferenceRequest.properties.basisPlanVersion").exists());
    }

    private ResultActions putAs(UUID actorId, UUID planId, String ifNoneMatch, String body) throws Exception {
        return putAs(actorId, planId, ifNoneMatch, null, body);
    }

    private ResultActions putAs(UUID actorId, UUID planId, String ifNoneMatch, String ifMatch, String body) throws Exception {
        var request = put("/api/v1/plans/{planId}/members/me/preference", planId).with(authentication(new org.springframework.security.authentication.TestingAuthenticationToken(new AuthenticatedActor(actorId, Set.of()), null, "ROLE_USER")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body).header(CorrelationIdFilter.HEADER_NAME, "preference-test");
        if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        return mockMvc.perform(request);
    }

    private ResultActions getAs(UUID actorId, String path, UUID planId) throws Exception {
        return mockMvc.perform(get(path, planId).with(authentication(new org.springframework.security.authentication.TestingAuthenticationToken(new AuthenticatedActor(actorId, Set.of()), null, "ROLE_USER"))).header(CorrelationIdFilter.HEADER_NAME, "preference-test"));
    }

    private String preference(UUID windowId) {
        return "{\"basisPlanVersion\":1,\"attendance\":\"JOINING\",\"guestCount\":1,\"selectedWindowIds\":[\"%s\"],\"personalBudget\":{\"currency\":\"PHP\",\"amount\":\"500.00\"},\"rankedPreferences\":[\"indoor court\",\"parking\"],\"privateNote\":\"I can bring equipment.\"}".formatted(windowId);
    }

    private String rankedPreferences(int count, int length) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> "\"" + ("r" + index + "x".repeat(length - 2)) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private UUID member() {
        var accountId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, 'Preference boundary member')")
                .param("accountId", accountId).update();
        jdbcClient.sql("INSERT INTO group_membership (group_id, account_id, role, status, joined_at) VALUES (:groupId, :accountId, 'MEMBER', 'ACTIVE', statement_timestamp())")
                .param("groupId", GROUP_ID).param("accountId", accountId).update();
        return accountId;
    }

    private ResultActions replaceRequirements(UUID... windowIds) throws Exception {
        var windows = java.util.stream.IntStream.range(0, windowIds.length)
                .mapToObj(index -> "{" + (WINDOW_ID.equals(windowIds[index]) ? "\"id\":\"" + windowIds[index] + "\"," : "")
                        + "\"startAt\":\"2027-01-" + (10 + index) + "T09:30:00Z\",\"endAt\":\"2027-01-" + (10 + index) + "T11:30:00Z\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        var body = "{\"title\":\"Changed preference plan\",\"category\":\"COURT\",\"timeZone\":\"Asia/Manila\",\"candidateWindows\":" + windows + ",\"area\":{\"code\":\"BGC\",\"radiusKm\":5},\"headcount\":{\"minimum\":4,\"maximum\":10},\"budget\":null,\"mustHaves\":[],\"providerSafeNotes\":null,\"categoryAttributes\":{}}";
        return mockMvc.perform(put("/api/v1/plans/{planId}/requirements", PLAN_ID)
                .with(authentication(new org.springframework.security.authentication.TestingAuthenticationToken(new AuthenticatedActor(ORGANIZER_ID, Set.of()), null, "ROLE_USER")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)
                .header("If-Match", "\"1\"").header(CorrelationIdFilter.HEADER_NAME, "preference-test"));
    }

    private CreatePreferenceCommand command(UUID actorId) {
        return new CreatePreferenceCommand(actorId, PLAN_ID, new CreatePreferenceRequest(1L, Attendance.JOINING, 1, List.of(WINDOW_ID), new CreatePreferenceRequest.PersonalBudgetRequest("PHP", "500.00"), List.of("indoor court", "parking"), "I can bring equipment."));
    }

    private Object createAfterBarrier(CreatePreferenceCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try { return preferenceService.create(command); } catch (PreferenceException exception) { return exception; }
    }

    private ReplacePreferenceCommand replaceCommand(UUID actorId, String privateNote) {
        return new ReplacePreferenceCommand(actorId, PLAN_ID, 1,
                new CreatePreferenceRequest(1L, Attendance.AVAILABLE, 2, List.of(WINDOW_ID),
                        new CreatePreferenceRequest.PersonalBudgetRequest("PHP", "750.00"),
                        List.of("air conditioning"), privateNote));
    }

    private Object replaceAfterBarrier(ReplacePreferenceCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try { return preferenceService.replace(command); } catch (PreferenceException exception) { return exception; }
    }

    private void pausePreferenceAfterGroupLock(CountDownLatch groupLocked, CountDownLatch allowContinuation) {
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            if (Thread.currentThread().getName().equals("preference-replacement")) {
                groupLocked.countDown();
                await(allowContinuation);
            }
            return result;
        }).when(AopTestUtils.<GroupMembershipAccess>getTargetObject(groupMembershipAccess))
                .lockAndHasActiveMembership(GROUP_ID, MEMBER_ID);
    }

    private void pauseRequirementAfterGroupLock(CountDownLatch groupLocked, CountDownLatch allowContinuation) {
        pauseGroupLockForThread("requirement-replacement", groupLocked, allowContinuation);
    }

    private void pauseRemovalAfterGroupLock(CountDownLatch groupLocked, CountDownLatch allowContinuation) {
        pauseGroupLockForThread("membership-removal", groupLocked, allowContinuation);
    }

    private void pauseGroupLockForThread(String threadName, CountDownLatch groupLocked, CountDownLatch allowContinuation) {
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            if (Thread.currentThread().getName().equals(threadName)) {
                groupLocked.countDown();
                await(allowContinuation);
            }
            return result;
        }).when(AopTestUtils.<GroupRepository>getTargetObject(groupRepository)).findAndLockGroup(GROUP_ID);
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for the transaction lock boundary.");
        }
    }

    private Object replacePreferenceOrReturnFailure(ReplacePreferenceCommand command) {
        try {
            return preferenceService.replace(command);
        } catch (PreferenceException exception) {
            return exception;
        }
    }

    private String removeMember() {
        return membershipExitService.remove(new RemoveGroupMemberCommand(
                ORGANIZER_ID, GROUP_ID, MEMBER_ID, "preference-membership-race", "preference-race-test"));
    }

    private ReplaceRequirementsCommand requirementReplacementCommand() {
        return new ReplaceRequirementsCommand(ORGANIZER_ID, PLAN_ID, 1,
                new RequirementReplacementRequest("Changed preference plan", ActivityCategory.COURT, "Asia/Manila",
                        List.of(new RequirementReplacementRequest.CandidateWindowRequest(WINDOW_ID,
                                OffsetDateTime.parse("2027-01-09T09:30:00Z"), OffsetDateTime.parse("2027-01-09T11:30:00Z"))),
                        new com.builtbyjuls.arat.planning.api.CreatePlanRequest.AreaRequest("BGC", 5),
                        new com.builtbyjuls.arat.planning.api.CreatePlanRequest.HeadcountRequest(4, 10),
                        null, List.of(), null, Map.of()),
                "preference-race-test");
    }

    private void insertPlan(UUID planId, UUID activeWindowId, UUID retiredWindowId) {
        jdbcClient.sql("INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version) VALUES (:planId, :groupId, 'Preference plan', 'COLLABORATING', :organizer, 1)").param("planId", planId).param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        jdbcClient.sql("INSERT INTO planning_requirement_draft (plan_id, category, time_zone, area_code, radius_km, minimum_headcount, maximum_headcount, category_attributes) VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, '{}'::jsonb)").param("planId", planId).update();
        jdbcClient.sql("INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at, retired_at) VALUES (:active, :planId, 1, '2027-01-09T09:00:00Z', '2027-01-09T11:00:00Z', NULL)").param("active", activeWindowId).param("planId", planId).update();
        if (retiredWindowId != null) jdbcClient.sql("INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at, retired_at) VALUES (:retired, :planId, 2, '2027-01-10T09:00:00Z', '2027-01-10T11:00:00Z', statement_timestamp())").param("retired", retiredWindowId).param("planId", planId).update();
    }

    private long count(String sql, UUID... windowId) {
        var query = jdbcClient.sql(sql);
        if (windowId.length > 0) query.param("windowId", windowId[0]);
        if (windowId.length > 1) query.param("accountId", windowId[1]);
        return query.query(Long.class).single();
    }
}
