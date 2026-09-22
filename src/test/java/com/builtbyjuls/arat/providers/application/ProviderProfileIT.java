package com.builtbyjuls.arat.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.providers.api.ProviderProfileException;
import com.builtbyjuls.arat.providers.api.ReplaceProviderProfileCommand;
import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import com.builtbyjuls.arat.testing.ConcurrentDatabaseWorkers;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderProfileIT extends PostgreSqlIntegrationTest {

    private static final UUID PROVIDER_ID = UUID.fromString("70000000-0000-4000-8000-000000000011");
    private static final UUID OTHER_PROVIDER_ID = UUID.fromString("70000000-0000-4000-8000-000000000012");
    private static final UUID ADMIN_ID = UUID.fromString("80000000-0000-4000-8000-000000000011");
    private static final UUID STAFF_ID = UUID.fromString("80000000-0000-4000-8000-000000000012");
    private static final UUID OTHER_ADMIN_ID = UUID.fromString("80000000-0000-4000-8000-000000000013");
    private static final UUID OUTSIDER_ID = UUID.fromString("80000000-0000-4000-8000-000000000014");
    private static final String CORRELATION_ID = "provider-profile-test-123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ProviderProfileService providerProfileService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM provider_service_area").update();
        jdbcClient.sql("DELETE FROM provider_supported_category").update();
        jdbcClient.sql("DELETE FROM provider_staff_membership").update();
        jdbcClient.sql("DELETE FROM provider_organization").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:adminId, :staffId, :otherAdminId, :outsiderId)")
                .param("adminId", ADMIN_ID)
                .param("staffId", STAFF_ID)
                .param("otherAdminId", OTHER_ADMIN_ID)
                .param("outsiderId", OUTSIDER_ID)
                .update();
        insertAccount(ADMIN_ID, "Provider administrator");
        insertAccount(STAFF_ID, "Provider staff member");
        insertAccount(OTHER_ADMIN_ID, "Other provider administrator");
        insertAccount(OUTSIDER_ID, "Provider outsider");
        insertProvider(PROVIDER_ID, "Original courts");
        insertProvider(OTHER_PROVIDER_ID, "Other provider");
        insertMembership(PROVIDER_ID, ADMIN_ID, "ADMIN", "ACTIVE");
        insertMembership(PROVIDER_ID, STAFF_ID, "STAFF", "ACTIVE");
        insertMembership(OTHER_PROVIDER_ID, ADMIN_ID, "STAFF", "ACTIVE");
        insertMembership(OTHER_PROVIDER_ID, OTHER_ADMIN_ID, "ADMIN", "ACTIVE");
        insertCategory(PROVIDER_ID, "COURT");
        insertArea(PROVIDER_ID, "BGC");
        insertCategory(OTHER_PROVIDER_ID, "KTV");
        insertArea(OTHER_PROVIDER_ID, "Makati");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void removeAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_provider_profile_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_provider_profile_audit()").update();
    }

    @Test
    void readsTheExplicitProviderForActiveAdminAndStaffOnly() throws Exception {
        readAs(ADMIN_ID, PROVIDER_ID)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.providerId").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.supportedCategories[0]").value("COURT"))
                .andExpect(jsonPath("$.serviceAreaCodes[0]").value("BGC"));
        readAs(STAFF_ID, PROVIDER_ID).andExpect(status().isOk());

        readAs(OTHER_ADMIN_ID, PROVIDER_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        readAs(OUTSIDER_ID, PROVIDER_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        readAs(ADMIN_ID, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));

        jdbcClient.sql("UPDATE provider_staff_membership SET status = 'REMOVED', removed_at = statement_timestamp() WHERE provider_id = :providerId AND account_id = :accountId")
                .param("providerId", PROVIDER_ID)
                .param("accountId", STAFF_ID)
                .update();
        readAs(STAFF_ID, PROVIDER_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
    }

    @Test
    void adminReplacesTheWholeProfileOnceWithoutChangingEligibilityVersion() throws Exception {
        replaceAs(ADMIN_ID, PROVIDER_ID, "\"1\"", replacementBody("Renamed venues", "[\"KTV\",\"GROUP_DINING\"]", "[\"Makati\",\"Quezon City\"]"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.displayName").value("Renamed venues"))
                .andExpect(jsonPath("$.supportedCategories[0]").value("GROUP_DINING"))
                .andExpect(jsonPath("$.supportedCategories[1]").value("KTV"))
                .andExpect(jsonPath("$.serviceAreaCodes[0]").value("Makati"))
                .andExpect(jsonPath("$.serviceAreaCodes[1]").value("Quezon City"));

        assertThat(providerVersion()).isEqualTo(2);
        assertThat(eligibilityVersion()).isOne();
        assertThat(categories()).containsExactly("GROUP_DINING", "KTV");
        assertThat(areas()).containsExactly("Makati", "Quezon City");
        assertThat(auditCount()).isOne();
        assertThat(auditMetadata()).contains("providerId").contains(PROVIDER_ID.toString())
                .doesNotContain("displayName").doesNotContain("serviceAreaCodes").doesNotContain("supportedCategories");
    }

    @Test
    void mutationDistinguishesActiveStaffRoleFromInvisibleProviderContexts() throws Exception {
        var body = replacementBody("New name", "[\"KTV\"]", "[\"Makati\"]");

        replaceAs(STAFF_ID, PROVIDER_ID, "\"1\"", body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        replaceAs(OTHER_ADMIN_ID, PROVIDER_ID, "\"1\"", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        replaceAs(OUTSIDER_ID, PROVIDER_ID, "\"1\"", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        replaceAs(ADMIN_ID, UUID.randomUUID(), "\"1\"", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));

        jdbcClient.sql("UPDATE provider_staff_membership SET status = 'REMOVED', removed_at = statement_timestamp() WHERE provider_id = :providerId AND account_id = :accountId")
                .param("providerId", PROVIDER_ID)
                .param("accountId", STAFF_ID)
                .update();
        replaceAs(STAFF_ID, PROVIDER_ID, "\"1\"", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        assertThat(providerVersion()).isOne();
        assertThat(auditCount()).isZero();
    }

    @Test
    void usesTheRouteProviderContextForAnAccountWithTwoMemberships() throws Exception {
        readAs(ADMIN_ID, PROVIDER_ID).andExpect(status().isOk());
        readAs(ADMIN_ID, OTHER_PROVIDER_ID).andExpect(status().isOk());
        replaceAs(ADMIN_ID, PROVIDER_ID, "\"1\"", replacementBody("Updated provider", "[\"KTV\"]", "[\"Makati\"]"))
                .andExpect(status().isOk());
        replaceAs(ADMIN_ID, OTHER_PROVIDER_ID, "\"1\"", replacementBody("Other update", "[\"COURT\"]", "[\"BGC\"]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        jdbcClient.sql("UPDATE provider_staff_membership SET status = 'REMOVED', removed_at = statement_timestamp() WHERE provider_id = :providerId AND account_id = :accountId")
                .param("providerId", OTHER_PROVIDER_ID)
                .param("accountId", ADMIN_ID)
                .update();
        readAs(ADMIN_ID, OTHER_PROVIDER_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        replaceAs(ADMIN_ID, OTHER_PROVIDER_ID, "\"1\"", replacementBody("Other update", "[\"COURT\"]", "[\"BGC\"]"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        assertThat(providerVersion()).isEqualTo(2);
    }

    @Test
    void rollsBackTheFullProfileReplacementWhenAuditAppendFails() throws Exception {
        jdbcClient.sql("""
                CREATE FUNCTION test_fail_provider_profile_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced provider profile audit failure'; END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_provider_profile_audit BEFORE INSERT ON audit_event
                FOR EACH ROW WHEN (NEW.action = 'provider.profile.replaced')
                EXECUTE FUNCTION test_fail_provider_profile_audit()
                """).update();

        replaceAs(ADMIN_ID, PROVIDER_ID, "\"1\"", replacementBody("New name", "[\"KTV\"]", "[\"Makati\"]"))
                .andExpect(status().isInternalServerError());

        assertThat(providerVersion()).isOne();
        assertThat(eligibilityVersion()).isOne();
        assertThat(providerName()).isEqualTo("Original courts");
        assertThat(categories()).containsExactly("COURT");
        assertThat(areas()).containsExactly("BGC");
        assertThat(auditCount()).isZero();
    }

    @Test
    void enforcesMissingMalformedAndStaleProviderPreconditions() throws Exception {
        var body = replacementBody("New name", "[\"KTV\"]", "[\"Makati\"]");
        mockMvc.perform(put("/api/v1/providers/{providerId}/profile", PROVIDER_ID)
                        .with(authentication(authenticationFor(ADMIN_ID)))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        replaceAs(ADMIN_ID, PROVIDER_ID, "1", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRECONDITION"));
        replaceAs(ADMIN_ID, PROVIDER_ID, "\"2\"", body)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(providerVersion()).isOne();
        assertThat(auditCount()).isZero();
    }

    @Test
    void concurrentWritersWithOneEtagLeaveOneCompleteWinningProfile() throws Exception {
        var first = command("First winning profile", List.of(ProviderCategory.KTV), List.of("Makati"));
        var second = command("Second losing profile", List.of(ProviderCategory.GROUP_DINING), List.of("Quezon City"));

        var outcomes = ConcurrentDatabaseWorkers.runOrdered(
                dataSource,
                transactionManager,
                "SELECT provider_id FROM provider_organization WHERE provider_id = ? FOR NO KEY UPDATE",
                PROVIDER_ID,
                () -> providerProfileService.replace(first),
                () -> providerProfileService.replace(second));

        assertThat(outcomes.getFirst().succeeded()).isTrue();
        assertThat(outcomes.get(1).failure())
                .isInstanceOf(ProviderProfileException.class)
                .extracting(error -> ((ProviderProfileException) error).reason())
                .isEqualTo(ProviderProfileException.Reason.PRECONDITION_FAILED);
        assertThat(providerVersion()).isEqualTo(2);
        assertThat(eligibilityVersion()).isOne();
        assertThat(providerName()).isEqualTo("First winning profile");
        assertThat(categories()).containsExactly("KTV");
        assertThat(areas()).containsExactly("Makati");
        assertThat(auditCount()).isOne();
    }

    @Test
    void publishesProviderRoutesAndRequiredProfilePreconditionInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}'].get.operationId").value("readProvider"))
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/profile'].put.operationId").value("replaceProviderProfile"))
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/profile'].put.parameters[?(@.name == 'If-Match')].required").value(true))
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/profile'].put.responses['200'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/profile'].put.responses['428']").exists());
    }

    private org.springframework.test.web.servlet.ResultActions readAs(UUID actorId, UUID providerId) throws Exception {
        return mockMvc.perform(get("/api/v1/providers/{providerId}", providerId)
                .with(authentication(authenticationFor(actorId)))
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private org.springframework.test.web.servlet.ResultActions replaceAs(UUID actorId, UUID providerId, String ifMatch, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/providers/{providerId}/profile", providerId)
                .with(authentication(authenticationFor(actorId)))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body)
                .header("If-Match", ifMatch)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private ReplaceProviderProfileCommand command(
            String displayName, List<ProviderCategory> categories, List<String> areas) {
        return new ReplaceProviderProfileCommand(
                ADMIN_ID, PROVIDER_ID, 1, displayName, categories, areas, CORRELATION_ID);
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId) {
        return new TestingAuthenticationToken(new AuthenticatedActor(accountId, java.util.Set.of()), null, "ROLE_USER");
    }

    private String replacementBody(String displayName, String categories, String areas) {
        return "{\"displayName\":\"%s\",\"supportedCategories\":%s,\"serviceAreaCodes\":%s}"
                .formatted(displayName, categories, areas);
    }

    private void insertAccount(UUID accountId, String displayName) {
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                .param("accountId", accountId)
                .param("displayName", displayName)
                .update();
    }

    private void insertProvider(UUID providerId, String displayName) {
        jdbcClient.sql("INSERT INTO provider_organization (provider_id, display_name) VALUES (:providerId, :displayName)")
                .param("providerId", providerId)
                .param("displayName", displayName)
                .update();
    }

    private void insertMembership(UUID providerId, UUID accountId, String role, String status) {
        jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (provider_id, account_id, role, status, removed_at)
                        VALUES (:providerId, :accountId, :role, :status,
                                CASE WHEN :status = 'REMOVED' THEN statement_timestamp() ELSE NULL END)
                        """)
                .param("providerId", providerId)
                .param("accountId", accountId)
                .param("role", role)
                .param("status", status)
                .update();
    }

    private void insertCategory(UUID providerId, String category) {
        jdbcClient.sql("INSERT INTO provider_supported_category (provider_id, category) VALUES (:providerId, :category)")
                .param("providerId", providerId)
                .param("category", category)
                .update();
    }

    private void insertArea(UUID providerId, String area) {
        jdbcClient.sql("INSERT INTO provider_service_area (provider_id, area_code) VALUES (:providerId, :area)")
                .param("providerId", providerId)
                .param("area", area)
                .update();
    }

    private long providerVersion() {
        return jdbcClient.sql("SELECT version FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID)
                .query(Long.class)
                .single();
    }

    private long eligibilityVersion() {
        return jdbcClient.sql("SELECT eligibility_version FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID)
                .query(Long.class)
                .single();
    }

    private String providerName() {
        return jdbcClient.sql("SELECT display_name FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID)
                .query(String.class)
                .single();
    }

    private List<String> categories() {
        return jdbcClient.sql("SELECT category FROM provider_supported_category WHERE provider_id = :providerId ORDER BY category")
                .param("providerId", PROVIDER_ID)
                .query(String.class)
                .list();
    }

    private List<String> areas() {
        return jdbcClient.sql("SELECT area_code FROM provider_service_area WHERE provider_id = :providerId ORDER BY area_code")
                .param("providerId", PROVIDER_ID)
                .query(String.class)
                .list();
    }

    private long auditCount() {
        return jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = 'provider.profile.replaced'")
                .query(Long.class)
                .single();
    }

    private String auditMetadata() {
        return jdbcClient.sql("SELECT metadata::TEXT FROM audit_event WHERE action = 'provider.profile.replaced'")
                .query(String.class)
                .single();
    }
}
