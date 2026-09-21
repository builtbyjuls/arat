package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.planning.api.CreatePlanRequest;
import com.builtbyjuls.arat.planning.api.ReplaceRequirementsCommand;
import com.builtbyjuls.arat.planning.api.RequirementReplacementException;
import com.builtbyjuls.arat.planning.api.RequirementReplacementRequest;
import com.builtbyjuls.arat.planning.api.RequirementRepresentation;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequirementReplacementIT extends PostgreSqlIntegrationTest {

    private static final UUID ORGANIZER_ID = UUID.fromString("74000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("74000000-0000-4000-8000-000000000002");
    private static final UUID OUTSIDER_ID = UUID.fromString("74000000-0000-4000-8000-000000000003");
    private static final UUID GROUP_ID = UUID.fromString("75000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("76000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_PLAN_ID = UUID.fromString("76000000-0000-4000-8000-000000000002");
    private static final UUID WINDOW_ID = UUID.fromString("77000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_WINDOW_ID = UUID.fromString("77000000-0000-4000-8000-000000000002");
    private static final UUID OMITTED_WINDOW_ID = UUID.fromString("77000000-0000-4000-8000-000000000003");
    private static final String CORRELATION_ID = "requirements-test-123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RequirementReplacementService requirementReplacementService;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        removeAuditFailureTrigger();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:organizerId, :memberId, :outsiderId)")
                .param("organizerId", ORGANIZER_ID)
                .param("memberId", MEMBER_ID)
                .param("outsiderId", OUTSIDER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name) VALUES
                            (:organizerId, 'Requirements organizer'),
                            (:memberId, 'Requirements member'),
                            (:outsiderId, 'Requirements outsider')
                        """)
                .param("organizerId", ORGANIZER_ID)
                .param("memberId", MEMBER_ID)
                .param("outsiderId", OUTSIDER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Requirements group', '', 'ACTIVE', :organizerId)
                        """)
                .param("groupId", GROUP_ID)
                .param("organizerId", ORGANIZER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at) VALUES
                            (:groupId, :organizerId, 'ORGANIZER', 'ACTIVE', statement_timestamp()),
                            (:groupId, :memberId, 'MEMBER', 'ACTIVE', statement_timestamp())
                        """)
                .param("groupId", GROUP_ID)
                .param("organizerId", ORGANIZER_ID)
                .param("memberId", MEMBER_ID)
                .update();
        insertPlan(PLAN_ID, WINDOW_ID, "Original plan");
        insertPlan(SECOND_PLAN_ID, SECOND_WINDOW_ID, "Second plan");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void removeAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_requirement_replacement_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_requirement_replacement_audit()").update();
    }

    @Test
    void organizerReplacesTheEntireDraftAndRetiresOnlyOmittedWindows() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at)
                        VALUES (:windowId, :planId, 2, '2027-01-10T09:00:00Z', '2027-01-10T11:00:00Z')
                        """).param("windowId", OMITTED_WINDOW_ID).param("planId", PLAN_ID).update();
        replaceAs(ORGANIZER_ID, PLAN_ID, "\"1\"", replacement(WINDOW_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.title").value("Updated plan"))
                .andExpect(jsonPath("$.candidateWindows[0].id").value(WINDOW_ID.toString()))
                .andExpect(jsonPath("$.candidateWindows[1].id").exists())
                .andExpect(jsonPath("$.mustHaves[0]").value("parking"));

        assertThat(planVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE plan_id = :planId AND retired_at IS NULL", PLAN_ID)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE plan_id = :planId AND candidate_window_id = :windowId AND retired_at IS NULL", PLAN_ID, WINDOW_ID)).isOne();
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE plan_id = :planId AND candidate_window_id = :windowId AND retired_at IS NOT NULL", PLAN_ID, OMITTED_WINDOW_ID)).isOne();
        assertThat(count("SELECT count(*) FROM planning_requirement_must_have WHERE plan_id = :planId", PLAN_ID)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced' AND plan_id = :planId", PLAN_ID)).isOne();
    }

    @Test
    void rejectsEveryPreconditionFailureBeforeMutation() throws Exception {
        replaceAs(ORGANIZER_ID, PLAN_ID, null, replacement(WINDOW_ID))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        replaceAs(ORGANIZER_ID, PLAN_ID, "1", replacement(WINDOW_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRECONDITION"));
        replaceAs(ORGANIZER_ID, PLAN_ID, "\"2\"", replacement(WINDOW_ID))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(planVersion()).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
    }

    @Test
    void rejectsActiveNonOrganizerAndOutsiderWithoutChangingTheDraft() throws Exception {
        replaceAs(MEMBER_ID, PLAN_ID, "\"1\"", replacement(WINDOW_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        replaceAs(OUTSIDER_ID, PLAN_ID, "\"1\"", replacement(WINDOW_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        assertThat(planVersion()).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
    }

    @Test
    void rejectsUnknownOrCrossPlanWindowIds() throws Exception {
        replaceAs(ORGANIZER_ID, PLAN_ID, "\"1\"", replacement(UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        replaceAs(ORGANIZER_ID, PLAN_ID, "\"1\"", replacement(SECOND_WINDOW_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(planVersion()).isOne();
    }

    @Test
    void rejectsCancelledPlans() throws Exception {
        jdbcClient.sql("UPDATE planning_plan SET state = 'CANCELLED' WHERE plan_id = :planId")
                .param("planId", PLAN_ID).update();
        replaceAs(ORGANIZER_ID, PLAN_ID, "\"1\"", replacement(WINDOW_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PLAN_STATE"));
        assertThat(planVersion()).isOne();
    }

    @Test
    void validatesTheReplacementShapeAndBounds() throws Exception {
        for (var body : List.of(
                replacement(WINDOW_ID).replace("Updated plan", ""),
                replacement(WINDOW_ID).replace("Updated plan", "x".repeat(121)),
                replacement(WINDOW_ID).replace("\"Asia/Manila\"", "\"+08:00\""),
                replacement(WINDOW_ID).replace("\"BGC\"", "\"\""),
                replacement(WINDOW_ID).replace("\"BGC\"", "\"" + "x".repeat(65) + "\""),
                replacement(WINDOW_ID).replace("\"radiusKm\":5", "\"radiusKm\":0"),
                replacement(WINDOW_ID).replace("\"radiusKm\":5", "\"radiusKm\":101"),
                replacement(WINDOW_ID).replace("\"minimum\":4", "\"minimum\":0"),
                replacement(WINDOW_ID).replace("\"maximum\":10", "\"maximum\":101"),
                replacement(WINDOW_ID).replace("\"minimum\":4,\"maximum\":10", "\"minimum\":11,\"maximum\":10"),
                replacement(WINDOW_ID).replace("\"currency\":\"PHP\"", "\"currency\":\"USD\""),
                replacement(WINDOW_ID).replace("\"minimumAmount\":\"0.00\"", "\"minimumAmount\":\"0.0\""),
                replacement(WINDOW_ID).replace("\"maximumAmount\":\"2500.00\"", "\"maximumAmount\":\"1000000.01\""),
                replacement(WINDOW_ID).replace("\"minimumAmount\":\"0.00\",\"maximumAmount\":\"2500.00\"", "\"minimumAmount\":\"2500.01\",\"maximumAmount\":\"2500.00\""),
                withCandidateWindows("[]"),
                withCandidateWindows(candidateWindows(11, null)),
                withCandidateWindows("[{\"id\":\"" + WINDOW_ID + "\",\"startAt\":\"2027-01-09T11:00:00Z\",\"endAt\":\"2027-01-09T11:00:00Z\"}]"),
                withCandidateWindows("[{\"id\":\"" + WINDOW_ID + "\",\"startAt\":\"2027-01-09T09:00:00Z\",\"endAt\":\"2027-01-09T11:00:00Z\"},{\"id\":\"" + WINDOW_ID + "\",\"startAt\":\"2027-01-10T09:00:00Z\",\"endAt\":\"2027-01-10T11:00:00Z\"}]"),
                replacement(WINDOW_ID).replace("\"mustHaves\":[\"parking\",\"shower\"]", "\"mustHaves\":[\"parking\",\"parking\"]"),
                replacement(WINDOW_ID).replace("\"mustHaves\":[\"parking\",\"shower\"]", "\"mustHaves\":[\"\"]"),
                replacement(WINDOW_ID).replace("\"mustHaves\":[\"parking\",\"shower\"]", "\"mustHaves\":[\"" + "x".repeat(121) + "\"]"),
                replacement(WINDOW_ID).replace("\"mustHaves\":[\"parking\",\"shower\"]", "\"mustHaves\":" + jsonArray("must", 21)),
                replacement(WINDOW_ID).replace("\"providerSafeNotes\":\"Indoor court preferred.\"", "\"providerSafeNotes\":\"" + "x".repeat(1001) + "\""),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"bad_key\":true}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"Attribute\":true}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"a" + "x".repeat(40) + "\":true}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"valid\":null}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"valid\":1.5}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"valid\":[]}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"valid\":{}}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"valid\":-1}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"valid\":1000001}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"valid\":\"\"}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":{\"valid\":\"" + "x".repeat(121) + "\"}"),
                replacement(WINDOW_ID).replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":" + attributes(21)))) {
            replaceAs(ORGANIZER_ID, PLAN_ID, "\"1\"", body)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(planVersion()).isOne();
    }

    @Test
    void acceptsTheDocumentedReplacementBoundaries() throws Exception {
        replaceAs(ORGANIZER_ID, PLAN_ID, "\"1\"", boundaryReplacement())
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));
        assertThat(planVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE plan_id = :planId AND retired_at IS NULL", PLAN_ID)).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM planning_requirement_must_have WHERE plan_id = :planId", PLAN_ID)).isEqualTo(20);
    }

    @Test
    void concurrentWritersWithOneEtagHaveOneWinnerAndNoMixedDraft() throws Exception {
        var barrier = new CyclicBarrier(2);
        var first = command("First concurrent title", WINDOW_ID, false);
        var second = command("Second concurrent title", WINDOW_ID, true);
        List<Object> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = List.of(
                    executor.submit(() -> replaceAfterBarrier(first, barrier)),
                    executor.submit(() -> replaceAfterBarrier(second, barrier)));
            results = List.of(outcomes.get(0).get(10, TimeUnit.SECONDS), outcomes.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(RequirementReplacementException.class::isInstance)
                    .map(RequirementReplacementException.class::cast)
                    .map(RequirementReplacementException::reason))
                    .containsExactly(RequirementReplacementException.Reason.PRECONDITION_FAILED);
        }
        assertThat(planVersion()).isEqualTo(2);
        var winners = results.stream().filter(RequirementRepresentation.class::isInstance)
                .map(RequirementRepresentation.class::cast).toList();
        assertThat(winners).hasSize(1);
        var winner = winners.getFirst();
        assertThat(planTitle()).isEqualTo(winner.title());
        assertDraftMatches(winner.title().equals("Second concurrent title"));
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced'")).isOne();
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE plan_id = :planId AND retired_at IS NULL", PLAN_ID)).isEqualTo(2);
    }

    @Test
    void rollsBackVersionChildrenAndAuditWhenAuditAppendFails() throws Exception {
        jdbcClient.sql("""
                        CREATE FUNCTION test_fail_requirement_replacement_audit()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'forced requirement audit failure'; END;
                        $$
                        """).update();
        jdbcClient.sql("""
                        CREATE TRIGGER fail_requirement_replacement_audit
                        BEFORE INSERT ON audit_event FOR EACH ROW
                        WHEN (NEW.action = 'plan.requirements.replaced')
                        EXECUTE FUNCTION test_fail_requirement_replacement_audit()
                        """).update();

        replaceAs(ORGANIZER_ID, PLAN_ID, "\"1\"", replacement(WINDOW_ID))
                .andExpect(status().isInternalServerError());

        assertThat(planVersion()).isOne();
        assertThat(planTitle()).isEqualTo("Original plan");
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE plan_id = :planId AND retired_at IS NULL", PLAN_ID)).isOne();
        assertThat(count("SELECT count(*) FROM planning_requirement_must_have WHERE plan_id = :planId", PLAN_ID)).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced'")).isZero();
    }

    @Test
    void publishesTheReplacementContractInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/requirements'].put.operationId").value("replacePlanRequirements"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/requirements'].put.parameters[?(@.name == 'If-Match')].required").value(true))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/requirements'].put.responses['428']").exists())
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.candidateWindows.items.$ref").value("#/components/schemas/CandidateWindowRequest"))
                .andExpect(jsonPath("$.components.schemas.CandidateWindowRequest.properties.id").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.RequirementReplacementCandidateWindow.properties.id").exists())
                .andExpect(jsonPath("$.components.schemas.PlanRequirements.properties.title").exists());
    }

    private org.springframework.test.web.servlet.ResultActions replaceAs(UUID actorId, UUID planId, String ifMatch, String body) throws Exception {
        var request = put("/api/v1/plans/{planId}/requirements", planId)
                .with(SecurityMockMvcRequestPostProcessors.authentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                        new com.builtbyjuls.arat.identity.api.AuthenticatedActor(actorId, java.util.Set.of()), null, "ROLE_USER")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mockMvc.perform(request);
    }

    private ReplaceRequirementsCommand command(String title, UUID retainedWindowId, boolean second) {
        return new ReplaceRequirementsCommand(
                ORGANIZER_ID,
                PLAN_ID,
                1,
                new RequirementReplacementRequest(
                        title,
                        second ? com.builtbyjuls.arat.planning.domain.ActivityCategory.KTV : com.builtbyjuls.arat.planning.domain.ActivityCategory.COURT,
                        "Asia/Manila",
                        List.of(
                                new RequirementReplacementRequest.CandidateWindowRequest(retainedWindowId,
                                        OffsetDateTime.parse(second ? "2027-01-11T09:00:00Z" : "2027-01-09T09:00:00Z"), OffsetDateTime.parse(second ? "2027-01-11T12:00:00Z" : "2027-01-09T11:00:00Z")),
                                new RequirementReplacementRequest.CandidateWindowRequest(null,
                                        OffsetDateTime.parse(second ? "2027-01-12T10:00:00Z" : "2027-01-10T09:00:00Z"), OffsetDateTime.parse(second ? "2027-01-12T13:00:00Z" : "2027-01-10T11:00:00Z"))),
                        new CreatePlanRequest.AreaRequest(second ? "Makati" : "BGC", second ? 8 : 5),
                        new CreatePlanRequest.HeadcountRequest(second ? 9 : 4, second ? 13 : 10),
                        new CreatePlanRequest.BudgetRequest("PHP", second ? "500.00" : "0.00", second ? "4000.00" : "2500.00"),
                        second ? List.of("private room", "snacks") : List.of("parking", "shower"),
                        second ? "Private KTV room preferred." : "Indoor court preferred.",
                        second ? Map.of("karaokeRooms", objectMapper.readTree("3")) : Map.of("hasParking", objectMapper.readTree("true"))),
                CORRELATION_ID);
    }

    private Object replaceAfterBarrier(ReplaceRequirementsCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return requirementReplacementService.replace(command);
        } catch (RequirementReplacementException exception) {
            return exception;
        }
    }

    private String replacement(UUID retainedWindowId) {
        return """
                {"title":"Updated plan","category":"COURT","timeZone":"Asia/Manila",
                "candidateWindows":[{"id":"%s","startAt":"2027-01-09T09:30:00Z","endAt":"2027-01-09T11:30:00Z"},
                {"startAt":"2027-01-10T09:00:00Z","endAt":"2027-01-10T11:00:00Z"}],
                "area":{"code":"BGC","radiusKm":5},"headcount":{"minimum":4,"maximum":10},
                "budget":{"currency":"PHP","minimumAmount":"0.00","maximumAmount":"2500.00"},
                "mustHaves":["parking","shower"],"providerSafeNotes":"Indoor court preferred.",
                "categoryAttributes":{"hasParking":true}}
                """.formatted(retainedWindowId);
    }

    private String withCandidateWindows(String candidateWindows) {
        return replacement(WINDOW_ID).replaceFirst(
                "(?s)\\\"candidateWindows\\\":\\[.*?\\],\\s*\\\"area\\\"",
                "\\\"candidateWindows\\\":" + candidateWindows + ",\\\"area\\\"");
    }

    private String candidateWindows(int count, UUID retainedWindowId) {
        var windows = new StringBuilder("[");
        for (var index = 0; index < count; index++) {
            if (index > 0) {
                windows.append(',');
            }
            windows.append('{');
            if (index == 0 && retainedWindowId != null) {
                windows.append("\"id\":\"").append(retainedWindowId).append("\",");
            }
            windows.append("\"startAt\":\"2027-01-09T")
                    .append(String.format("%02d", index))
                    .append(":00:00Z\",\"endAt\":\"2027-01-09T")
                    .append(String.format("%02d", index + 1))
                    .append(":00:00Z\"}");
        }
        return windows.append(']').toString();
    }

    private String jsonArray(String prefix, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> "\"" + prefix + index + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private String attributes(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> "\"attribute" + index + "\":true")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private String boundaryReplacement() {
        var mustHaves = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> "\"" + ("m" + index + "x".repeat(119 - Integer.toString(index).length())) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        var boundaryAttributes = "{\"booleanValue\":true,\"minimumValue\":0,\"maximumValue\":1000000,\"textValue\":\""
                + "x".repeat(120) + "\"," + java.util.stream.IntStream.range(0, 16)
                .mapToObj(index -> "\"attribute" + index + "\":true")
                .collect(java.util.stream.Collectors.joining(",")) + "}";
        return withCandidateWindows(candidateWindows(10, WINDOW_ID))
                .replace("Updated plan", "x")
                .replace("\"Asia/Manila\"", "\"UTC\"")
                .replace("\"BGC\"", "\"" + "x".repeat(64) + "\"")
                .replace("\"radiusKm\":5", "\"radiusKm\":1")
                .replace("\"minimum\":4,\"maximum\":10", "\"minimum\":1,\"maximum\":100")
                .replace("\"minimumAmount\":\"0.00\",\"maximumAmount\":\"2500.00\"", "\"minimumAmount\":\"0.00\",\"maximumAmount\":\"1000000.00\"")
                .replace("\"mustHaves\":[\"parking\",\"shower\"]", "\"mustHaves\":" + mustHaves)
                .replace("\"providerSafeNotes\":\"Indoor court preferred.\"", "\"providerSafeNotes\":\"" + "x".repeat(1000) + "\"")
                .replace("\"categoryAttributes\":{\"hasParking\":true}", "\"categoryAttributes\":" + boundaryAttributes);
    }

    private void insertPlan(UUID planId, UUID windowId, String title) {
        jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version)
                        VALUES (:planId, :groupId, :title, 'COLLABORATING', :organizerId, 1)
                        """)
                .param("planId", planId).param("groupId", GROUP_ID).param("title", title).param("organizerId", ORGANIZER_ID).update();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km, minimum_headcount, maximum_headcount,
                            budget_currency, budget_minimum_minor_units, budget_maximum_minor_units, provider_safe_notes, category_attributes)
                        VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, 'PHP', 0, 250000, 'Original notes', '{}'::jsonb)
                        """).param("planId", planId).update();
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at)
                        VALUES (:windowId, :planId, 1, '2027-01-09T09:00:00Z', '2027-01-09T11:00:00Z')
                        """).param("windowId", windowId).param("planId", planId).update();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_must_have (plan_id, must_have, sort_order)
                        VALUES (:planId, 'original', 1)
                        """).param("planId", planId).update();
    }

    private long planVersion() {
        return jdbcClient.sql("SELECT version FROM planning_plan WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(Long.class).single();
    }

    private String planTitle() {
        return jdbcClient.sql("SELECT title FROM planning_plan WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(String.class).single();
    }

    private void assertDraftMatches(boolean second) {
        Map<String, Object> row = jdbcClient.sql("""
                        SELECT d.category, d.area_code, d.radius_km, d.minimum_headcount, d.maximum_headcount,
                               d.budget_minimum_minor_units, d.budget_maximum_minor_units, d.provider_safe_notes,
                               d.category_attributes::text AS category_attributes,
                               ARRAY(SELECT must_have FROM planning_requirement_must_have WHERE plan_id = p.plan_id ORDER BY sort_order) AS must_haves,
                               ARRAY(SELECT starts_at FROM planning_candidate_window WHERE plan_id = p.plan_id AND retired_at IS NULL ORDER BY sort_order) AS starts
                        FROM planning_plan p JOIN planning_requirement_draft d ON d.plan_id = p.plan_id
                        WHERE p.plan_id = :planId
                        """).param("planId", PLAN_ID).query((resultSet, rowNum) -> Map.<String, Object>ofEntries(
                Map.entry("category", resultSet.getString("category")),
                Map.entry("area", resultSet.getString("area_code")),
                Map.entry("radius", resultSet.getInt("radius_km")),
                Map.entry("minimum", resultSet.getInt("minimum_headcount")),
                Map.entry("maximum", resultSet.getInt("maximum_headcount")),
                Map.entry("budgetMinimum", resultSet.getLong("budget_minimum_minor_units")),
                Map.entry("budgetMaximum", resultSet.getLong("budget_maximum_minor_units")),
                Map.entry("notes", resultSet.getString("provider_safe_notes")),
                Map.entry("attributes", resultSet.getString("category_attributes")),
                Map.entry("mustHaves", java.util.Arrays.asList((Object[]) resultSet.getArray("must_haves").getArray())),
                Map.entry("starts", java.util.Arrays.stream((Object[]) resultSet.getArray("starts").getArray())
                        .map(java.sql.Timestamp.class::cast).map(java.sql.Timestamp::toInstant).toList())))
                .single();
        if (second) {
            assertThat(row).containsEntry("category", "KTV").containsEntry("area", "Makati")
                    .containsEntry("radius", 8).containsEntry("minimum", 9).containsEntry("maximum", 13)
                    .containsEntry("budgetMinimum", 50000L).containsEntry("budgetMaximum", 400000L)
                    .containsEntry("notes", "Private KTV room preferred.").containsEntry("attributes", "{\"karaokeRooms\": 3}")
                    .containsEntry("mustHaves", List.of("private room", "snacks"))
                    .containsEntry("starts", List.of(java.time.Instant.parse("2027-01-11T09:00:00Z"), java.time.Instant.parse("2027-01-12T10:00:00Z")));
            return;
        }
        assertThat(row).containsEntry("category", "COURT").containsEntry("area", "BGC")
                .containsEntry("radius", 5).containsEntry("minimum", 4).containsEntry("maximum", 10)
                .containsEntry("budgetMinimum", 0L).containsEntry("budgetMaximum", 250000L)
                .containsEntry("notes", "Indoor court preferred.").containsEntry("attributes", "{\"hasParking\": true}")
                .containsEntry("mustHaves", List.of("parking", "shower"))
                .containsEntry("starts", List.of(java.time.Instant.parse("2027-01-09T09:00:00Z"), java.time.Instant.parse("2027-01-10T09:00:00Z")));
    }

    private long count(String sql, UUID... values) {
        var query = jdbcClient.sql(sql).param("planId", PLAN_ID);
        if (values.length > 1) {
            query.param("windowId", values[1]);
        }
        return query.query(Long.class).single();
    }
}
