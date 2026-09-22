package com.builtbyjuls.arat.providers.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.providers-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderEligibilityAccessIT extends PostgreSqlIntegrationTest {

    private static final UUID FIRST_PROVIDER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_PROVIDER_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID THIRD_PROVIDER_ID = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID STAFF_ACCOUNT_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_STAFF_ACCOUNT_ID = UUID.fromString("50000000-0000-4000-8000-000000000005");

    @Autowired
    private ProviderEligibilityAccess eligibilityAccess;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("TRUNCATE TABLE provider_organization CASCADE").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:staffAccountId, :otherStaffAccountId)")
                .param("staffAccountId", STAFF_ACCOUNT_ID)
                .param("otherStaffAccountId", OTHER_STAFF_ACCOUNT_ID)
                .update();
        insertAccount(STAFF_ACCOUNT_ID, "Provider staff");
        insertAccount(OTHER_STAFF_ACCOUNT_ID, "Other provider staff");
    }

    @Test
    void returnsDistinctVerifiedCandidatesInProviderIdOrderWithObservedEligibilityVersionsAndBound() {
        insertProvider(SECOND_PROVIDER_ID, "Second provider", "VERIFIED", 17);
        insertProvider(FIRST_PROVIDER_ID, "First provider", "VERIFIED", 9);
        insertProvider(THIRD_PROVIDER_ID, "Third provider", "VERIFIED", 23);
        addCriteria(SECOND_PROVIDER_ID, "COURT", "Makati");
        addCriteria(FIRST_PROVIDER_ID, "COURT", "Makati");
        addCriteria(FIRST_PROVIDER_ID, "KTV", "BGC");
        addCriteria(THIRD_PROVIDER_ID, "COURT", "Makati");

        assertThat(eligibilityAccess.findVerifiedCandidates(new ProviderEligibilityCriteria("COURT", "Makati"), 2))
                .containsExactly(
                        new ProviderEligibilityCandidate(FIRST_PROVIDER_ID, 9),
                        new ProviderEligibilityCandidate(SECOND_PROVIDER_ID, 17));
    }

    @Test
    void guardsStaffAccessInTheExplicitProviderContextIncludingMultipleAndRemovedMemberships() {
        insertProvider(FIRST_PROVIDER_ID, "First provider", "VERIFIED", 3);
        insertProvider(SECOND_PROVIDER_ID, "Second provider", "VERIFIED", 5);
        insertProvider(THIRD_PROVIDER_ID, "Third provider", "VERIFIED", 7);
        addMembership(FIRST_PROVIDER_ID, STAFF_ACCOUNT_ID, "ACTIVE");
        addMembership(SECOND_PROVIDER_ID, STAFF_ACCOUNT_ID, "ACTIVE");
        addMembership(THIRD_PROVIDER_ID, STAFF_ACCOUNT_ID, "REMOVED");
        addMembership(SECOND_PROVIDER_ID, OTHER_STAFF_ACCOUNT_ID, "ACTIVE");

        assertThat(eligibilityAccess.hasActiveStaffAccess(FIRST_PROVIDER_ID, STAFF_ACCOUNT_ID)).isTrue();
        assertThat(eligibilityAccess.hasActiveStaffAccess(SECOND_PROVIDER_ID, STAFF_ACCOUNT_ID)).isTrue();
        assertThat(eligibilityAccess.hasActiveStaffAccess(THIRD_PROVIDER_ID, STAFF_ACCOUNT_ID)).isFalse();
        assertThat(eligibilityAccess.hasActiveStaffAccess(FIRST_PROVIDER_ID, OTHER_STAFF_ACCOUNT_ID)).isFalse();
        assertThat(inTransaction(() -> eligibilityAccess.lockAndHasActiveStaffAccess(FIRST_PROVIDER_ID, STAFF_ACCOUNT_ID)))
                .isTrue();
    }

    @Test
    void guardsCurrentVerifiedEligibilityForEveryVerificationState() {
        insertProvider(FIRST_PROVIDER_ID, "First provider", "UNVERIFIED", 3);

        for (var verificationStatus : ProviderVerificationStatus.values()) {
            var currentSubmissionId = verificationStatus == ProviderVerificationStatus.PENDING
                    ? insertSubmission(FIRST_PROVIDER_ID)
                    : null;
            jdbcClient.sql("""
                            UPDATE provider_organization
                            SET verification_status = :verificationStatus,
                                current_verification_submission_id = :currentSubmissionId
                            WHERE provider_id = :providerId
                            """)
                    .param("verificationStatus", verificationStatus.name())
                    .param("currentSubmissionId", currentSubmissionId)
                    .param("providerId", FIRST_PROVIDER_ID)
                    .update();
            var expected = verificationStatus == ProviderVerificationStatus.VERIFIED;

            assertThat(eligibilityAccess.hasCurrentVerifiedEligibility(FIRST_PROVIDER_ID, 3)).isEqualTo(expected);
            assertThat(inTransaction(() -> eligibilityAccess.lockAndHasCurrentVerifiedEligibility(FIRST_PROVIDER_ID, 3)))
                    .isEqualTo(expected);
        }
    }

    @Test
    void requiresTheCapturedEligibilityVersionAfterEligibilityStateChanges() {
        insertProvider(FIRST_PROVIDER_ID, "First provider", "VERIFIED", 3);

        assertThat(eligibilityAccess.hasCurrentVerifiedEligibility(FIRST_PROVIDER_ID, 3)).isTrue();
        jdbcClient.sql("UPDATE provider_organization SET version = version + 1 WHERE provider_id = :providerId")
                .param("providerId", FIRST_PROVIDER_ID)
                .update();
        assertThat(eligibilityAccess.hasCurrentVerifiedEligibility(FIRST_PROVIDER_ID, 3)).isTrue();

        jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = 'SUSPENDED', eligibility_version = eligibility_version + 1
                        WHERE provider_id = :providerId
                        """)
                .param("providerId", FIRST_PROVIDER_ID)
                .update();
        assertThat(eligibilityAccess.hasCurrentVerifiedEligibility(FIRST_PROVIDER_ID, 3)).isFalse();

        jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = 'VERIFIED', eligibility_version = eligibility_version + 1
                        WHERE provider_id = :providerId
                        """)
                .param("providerId", FIRST_PROVIDER_ID)
                .update();
        assertThat(eligibilityAccess.hasCurrentVerifiedEligibility(FIRST_PROVIDER_ID, 3)).isFalse();
        assertThat(inTransaction(() -> eligibilityAccess.lockAndHasCurrentVerifiedEligibility(FIRST_PROVIDER_ID, 5)))
                .isTrue();
    }

    @Test
    void installsTheCriteriaLookupIndexes() {
        var indexNames = jdbcClient.sql("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND indexname IN (
                              'provider_supported_category_eligibility_lookup_idx',
                              'provider_service_area_eligibility_lookup_idx'
                          )
                        ORDER BY indexname
                        """)
                .query(String.class)
                .list();

        assertThat(indexNames).containsExactly(
                "provider_service_area_eligibility_lookup_idx",
                "provider_supported_category_eligibility_lookup_idx");
    }

    private void insertProvider(UUID providerId, String displayName, String verificationStatus, long eligibilityVersion) {
        jdbcClient.sql("""
                        INSERT INTO provider_organization (
                            provider_id, display_name, status, verification_status, version, eligibility_version
                        ) VALUES (:providerId, :displayName, 'ACTIVE', :verificationStatus, 1, :eligibilityVersion)
                        """)
                .param("providerId", providerId)
                .param("displayName", displayName)
                .param("verificationStatus", verificationStatus)
                .param("eligibilityVersion", eligibilityVersion)
                .update();
    }

    private void addCriteria(UUID providerId, String category, String areaCode) {
        jdbcClient.sql("INSERT INTO provider_supported_category (provider_id, category) VALUES (:providerId, :category)")
                .param("providerId", providerId)
                .param("category", category)
                .update();
        jdbcClient.sql("INSERT INTO provider_service_area (provider_id, area_code) VALUES (:providerId, :areaCode)")
                .param("providerId", providerId)
                .param("areaCode", areaCode)
                .update();
    }

    private void addMembership(UUID providerId, UUID accountId, String status) {
        jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (provider_id, account_id, role, status, removed_at)
                        VALUES (:providerId, :accountId, 'STAFF', :status,
                                CASE WHEN :status = 'REMOVED' THEN statement_timestamp() ELSE NULL END)
                        """)
                .param("providerId", providerId)
                .param("accountId", accountId)
                .param("status", status)
                .update();
    }

    private UUID insertSubmission(UUID providerId) {
        var submissionId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                            INSERT INTO provider_verification_submission (
                                submission_id, provider_id, submitted_by_account_id, evidence_count
                            ) VALUES (:submissionId, :providerId, :accountId, 1)
                            """)
                    .param("submissionId", submissionId)
                    .param("providerId", providerId)
                    .param("accountId", STAFF_ACCOUNT_ID)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO provider_verification_evidence_reference (
                                submission_id, sort_order, evidence_reference
                            ) VALUES (:submissionId, 1, 'evidence-reference')
                            """)
                    .param("submissionId", submissionId)
                    .update();
        });
        return submissionId;
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
