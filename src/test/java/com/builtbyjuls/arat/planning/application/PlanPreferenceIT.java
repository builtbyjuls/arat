package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.planning.api.CreatePreferenceCommand;
import com.builtbyjuls.arat.planning.api.CreatePreferenceRequest;
import com.builtbyjuls.arat.planning.api.PreferenceException;
import com.builtbyjuls.arat.planning.api.PreferenceRepresentation;
import com.builtbyjuls.arat.planning.domain.Attendance;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
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
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
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
    void publishesPreferenceContractInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/members/me/preference'].put.operationId").value("createMemberPreference"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/members/me/preference'].put.parameters[?(@.name == 'If-None-Match')].required").value(true))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/preferences'].get.operationId").value("listPlanPreferences"))
                .andExpect(jsonPath("$.components.schemas.CreatePreferenceRequest.properties.basisPlanVersion").exists());
    }

    private ResultActions putAs(UUID actorId, UUID planId, String ifNoneMatch, String body) throws Exception {
        var request = put("/api/v1/plans/{planId}/members/me/preference", planId).with(authentication(new org.springframework.security.authentication.TestingAuthenticationToken(new AuthenticatedActor(actorId, Set.of()), null, "ROLE_USER")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body).header(CorrelationIdFilter.HEADER_NAME, "preference-test");
        if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch);
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

    private void insertPlan(UUID planId, UUID activeWindowId, UUID retiredWindowId) {
        jdbcClient.sql("INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version) VALUES (:planId, :groupId, 'Preference plan', 'COLLABORATING', :organizer, 1)").param("planId", planId).param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        jdbcClient.sql("INSERT INTO planning_requirement_draft (plan_id, category, time_zone, area_code, radius_km, minimum_headcount, maximum_headcount, category_attributes) VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, '{}'::jsonb)").param("planId", planId).update();
        jdbcClient.sql("INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at, retired_at) VALUES (:active, :planId, 1, '2027-01-09T09:00:00Z', '2027-01-09T11:00:00Z', NULL)").param("active", activeWindowId).param("planId", planId).update();
        if (retiredWindowId != null) jdbcClient.sql("INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at, retired_at) VALUES (:retired, :planId, 2, '2027-01-10T09:00:00Z', '2027-01-10T11:00:00Z', statement_timestamp())").param("retired", retiredWindowId).param("planId", planId).update();
    }

    private long count(String sql, UUID... windowId) {
        var query = jdbcClient.sql(sql);
        if (windowId.length > 0) query.param("windowId", windowId[0]);
        return query.query(Long.class).single();
    }
}
