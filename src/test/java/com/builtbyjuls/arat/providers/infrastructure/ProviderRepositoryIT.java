package com.builtbyjuls.arat.providers.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import com.builtbyjuls.arat.providers.domain.ProviderOrganizationStatus;
import com.builtbyjuls.arat.providers.domain.ProviderStaffMembership;
import com.builtbyjuls.arat.providers.domain.ProviderStaffRole;
import com.builtbyjuls.arat.providers.domain.ProviderStaffStatus;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.testing.ConcurrentDatabaseWorkers;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.providers-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderRepositoryIT extends PostgreSqlIntegrationTest {

    private static final UUID PROVIDER_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID STAFF_ID = UUID.fromString("80000000-0000-4000-8000-000000000002");

    @Autowired
    private ProviderRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("DELETE FROM provider_service_area").update();
        jdbcClient.sql("DELETE FROM provider_supported_category").update();
        jdbcClient.sql("DELETE FROM provider_staff_membership").update();
        jdbcClient.sql("DELETE FROM provider_organization").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:accountId, :staffId)")
                .param("accountId", ACCOUNT_ID)
                .param("staffId", STAFF_ID)
                .update();
        insertAccount(ACCOUNT_ID, "Provider admin");
        insertAccount(STAFF_ID, "Provider staff");
    }

    @Test
    void persistsAndMapsProviderOrganizationStaffCapabilitiesAndAreas() {
        var timestamp = OffsetDateTime.parse("2026-09-22T08:00:00Z");
        var organization = organization(timestamp, ProviderVerificationStatus.VERIFIED);
        var staffMembership = new ProviderStaffMembership(
                PROVIDER_ID, STAFF_ID, ProviderStaffRole.STAFF, ProviderStaffStatus.ACTIVE, timestamp, null);

        inTransaction(() -> {
            repository.insert(
                    organization,
                    List.of(activeAdmin(timestamp), staffMembership),
                    List.of(ProviderCategory.KTV, ProviderCategory.COURT),
                    List.of("Makati", "BGC"));
            assertThat(repository.lockOrganization(PROVIDER_ID)).isEqualTo(organization);
            return null;
        });

        assertThat(repository.findById(PROVIDER_ID)).contains(organization);
        assertThat(repository.findStaffMembership(PROVIDER_ID, STAFF_ID)).contains(staffMembership);
        assertThat(repository.findSupportedCategories(PROVIDER_ID))
                .containsExactly(ProviderCategory.COURT, ProviderCategory.KTV);
        assertThat(repository.findServiceAreaCodes(PROVIDER_ID)).containsExactly("BGC", "Makati");
    }

    @Test
    void databaseRejectsInvalidProviderAndMembershipFactsAndDuplicates() {
        assertThatThrownBy(() -> insertOrganization(UUID.randomUUID(), "Bad provider", "INACTIVE", "UNVERIFIED", 1, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOrganization(UUID.randomUUID(), "Bad provider", "ACTIVE", "UNKNOWN", 1, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOrganization(UUID.randomUUID(), "Bad provider", "ACTIVE", "UNVERIFIED", 0, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOrganization(UUID.randomUUID(), "Bad provider", "ACTIVE", "UNVERIFIED", 1, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOrganization(UUID.randomUUID(), "\t", "ACTIVE", "UNVERIFIED", 1, 1))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertOrganization();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (provider_id, account_id, role, status, removed_at)
                        VALUES (:providerId, :accountId, 'OWNER', 'ACTIVE', NULL)
                        """)
                .param("providerId", PROVIDER_ID)
                .param("accountId", ACCOUNT_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (provider_id, account_id, role, status)
                        VALUES (:providerId, :accountId, 'ADMIN', 'INACTIVE')
                        """)
                .param("providerId", PROVIDER_ID)
                .param("accountId", ACCOUNT_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (provider_id, account_id, role, status, removed_at)
                        VALUES (:providerId, :accountId, 'ADMIN', 'REMOVED', NULL)
                        """)
                .param("providerId", PROVIDER_ID)
                .param("accountId", ACCOUNT_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        insertActiveAdmin();
        assertThatThrownBy(this::insertActiveAdmin).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO provider_supported_category (provider_id, category)
                        VALUES (:providerId, 'BOWLING')
                        """)
                .param("providerId", PROVIDER_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        insertCategory("COURT");
        assertThatThrownBy(() -> insertCategory("COURT")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO provider_service_area (provider_id, area_code)
                        VALUES (:providerId, E'\\t')
                        """)
                .param("providerId", PROVIDER_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        insertArea("BGC");
        assertThatThrownBy(() -> insertArea("BGC")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseLimitsDistinctServiceAreasToTwenty() {
        insertOrganization();
        for (var index = 1; index <= 20; index++) {
            insertArea("Area-" + index);
        }

        assertThatThrownBy(() -> insertArea("Area-21")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void databasePreventsAreaReassignmentAndConcurrentInsertsBeyondTheLimit() throws Exception {
        insertOrganization();
        var secondProviderId = UUID.randomUUID();
        insertOrganization(secondProviderId, "Second provider", "ACTIVE", "UNVERIFIED", 1, 1);
        for (var index = 1; index <= 20; index++) {
            insertArea("Area-" + index);
        }
        jdbcClient.sql("INSERT INTO provider_service_area (provider_id, area_code) VALUES (:providerId, 'Other area')")
                .param("providerId", secondProviderId)
                .update();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE provider_service_area
                        SET provider_id = :providerId
                        WHERE provider_id = :secondProviderId
                        """)
                .param("providerId", PROVIDER_ID)
                .param("secondProviderId", secondProviderId)
                .update()).isInstanceOf(DataAccessException.class);

        jdbcClient.sql("DELETE FROM provider_service_area WHERE provider_id = :providerId AND area_code = 'Area-20'")
                .param("providerId", PROVIDER_ID)
                .update();
        var outcomes = ConcurrentDatabaseWorkers.runOrdered(
                dataSource,
                transactionManager,
                "SELECT provider_id FROM provider_organization WHERE provider_id = ? FOR NO KEY UPDATE",
                PROVIDER_ID,
                () -> {
                    insertArea("Area-20");
                    return null;
                },
                () -> {
                    insertArea("Area-21");
                    return null;
                });
        assertThat(outcomes.getFirst().succeeded()).isTrue();
        assertThat(outcomes.get(1).failure())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("provider service area limit exceeded");
        assertThat(jdbcClient.sql("SELECT count(*) FROM provider_service_area WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID)
                .query(Long.class)
                .single()).isEqualTo(20);
    }

    @Test
    void rollsBackProviderRootAndChildrenTogether() {
        var timestamp = OffsetDateTime.parse("2026-09-22T08:00:00Z");
        assertThatThrownBy(() -> inTransaction(() -> {
            repository.insert(
                    organization(timestamp, ProviderVerificationStatus.UNVERIFIED),
                    List.of(activeAdmin(timestamp)),
                    List.of(ProviderCategory.COURT),
                    List.of("BGC"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.findById(PROVIDER_ID)).isEmpty();
        assertThat(jdbcClient.sql("SELECT count(*) FROM provider_staff_membership WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID)
                .query(Long.class)
                .single()).isZero();
    }

    private ProviderOrganization organization(OffsetDateTime timestamp, ProviderVerificationStatus verificationStatus) {
        return new ProviderOrganization(
                PROVIDER_ID,
                "Weekend venues",
                ProviderOrganizationStatus.ACTIVE,
                verificationStatus,
                1,
                1,
                timestamp,
                timestamp);
    }

    private ProviderStaffMembership activeAdmin(OffsetDateTime timestamp) {
        return new ProviderStaffMembership(
                PROVIDER_ID, ACCOUNT_ID, ProviderStaffRole.ADMIN, ProviderStaffStatus.ACTIVE, timestamp, null);
    }

    private void insertOrganization() {
        insertOrganization(PROVIDER_ID, "Weekend venues", "ACTIVE", "UNVERIFIED", 1, 1);
    }

    private void insertOrganization(
            UUID providerId, String displayName, String status, String verificationStatus, long version, long eligibilityVersion) {
        jdbcClient.sql("""
                        INSERT INTO provider_organization (
                            provider_id, display_name, status, verification_status, version, eligibility_version
                        ) VALUES (:providerId, :displayName, :status, :verificationStatus, :version, :eligibilityVersion)
                        """)
                .param("providerId", providerId)
                .param("displayName", displayName)
                .param("status", status)
                .param("verificationStatus", verificationStatus)
                .param("version", version)
                .param("eligibilityVersion", eligibilityVersion)
                .update();
    }

    private void insertActiveAdmin() {
        jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (provider_id, account_id, role, status)
                        VALUES (:providerId, :accountId, 'ADMIN', 'ACTIVE')
                        """)
                .param("providerId", PROVIDER_ID)
                .param("accountId", ACCOUNT_ID)
                .update();
    }

    private void insertCategory(String category) {
        jdbcClient.sql("INSERT INTO provider_supported_category (provider_id, category) VALUES (:providerId, :category)")
                .param("providerId", PROVIDER_ID)
                .param("category", category)
                .update();
    }

    private void insertArea(String areaCode) {
        jdbcClient.sql("INSERT INTO provider_service_area (provider_id, area_code) VALUES (:providerId, :areaCode)")
                .param("providerId", PROVIDER_ID)
                .param("areaCode", areaCode)
                .update();
    }

    private void insertAccount(UUID accountId, String displayName) {
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                .param("accountId", accountId)
                .param("displayName", displayName)
                .update();
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
        return transactionTemplate.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

}
