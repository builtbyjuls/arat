package com.builtbyjuls.arat.matching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.matching.api.MatchRequestRecipientsCommand;
import com.builtbyjuls.arat.matching.api.MatchingAccess;
import com.builtbyjuls.arat.matching.api.RequestRecipientCandidate;
import com.builtbyjuls.arat.matching.api.RequestRecipientSelection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "arat.test.matching-context=true",
        "arat.matching.recipient-cap=2"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecipientMatchingIT extends PostgreSqlIntegrationTest {

    private static final UUID FIRST_PROVIDER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_PROVIDER_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID THIRD_PROVIDER_ID = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID FOURTH_PROVIDER_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID LAST_LOWER_HALF_PROVIDER_ID = UUID.fromString("7fffffff-ffff-4fff-8fff-ffffffffffff");
    private static final UUID FIRST_UPPER_HALF_PROVIDER_ID = UUID.fromString("80000000-0000-4000-8000-000000000000");

    @Autowired
    private MatchingAccess matchingAccess;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void prepareDatabase() {
        jdbcClient.sql("TRUNCATE TABLE provider_organization CASCADE").update();
    }

    @Test
    void selectsOnlyExactVerifiedCategoryAndAreaMatchesOnceInUuidOrder() {
        insertProvider(SECOND_PROVIDER_ID, "VERIFIED");
        insertProvider(FIRST_PROVIDER_ID, "VERIFIED");
        insertProvider(THIRD_PROVIDER_ID, "UNVERIFIED");
        insertProvider(FOURTH_PROVIDER_ID, "VERIFIED");
        addCriteria(SECOND_PROVIDER_ID, "COURT", "BGC");
        addCriteria(FIRST_PROVIDER_ID, "COURT", "BGC");
        addCriteria(FIRST_PROVIDER_ID, "KTV", "Makati");
        addCriteria(THIRD_PROVIDER_ID, "COURT", "BGC");
        addCriteria(FOURTH_PROVIDER_ID, "KTV", "BGC");

        assertThat(matchingAccess.selectRecipients(new MatchRequestRecipientsCommand("COURT", "BGC")))
                .isEqualTo(new RequestRecipientSelection.Candidates(java.util.List.of(
                        new RequestRecipientCandidate(FIRST_PROVIDER_ID, 1),
                        new RequestRecipientCandidate(SECOND_PROVIDER_ID, 1))));
    }

    @Test
    void distinguishesZeroCandidatesExactlyAtTheCapAndCapOverflow() {
        assertThat(matchingAccess.selectRecipients(new MatchRequestRecipientsCommand("COURT", "BGC")))
                .isInstanceOf(RequestRecipientSelection.NoCandidates.class);

        insertProvider(FIRST_PROVIDER_ID, "VERIFIED");
        insertProvider(SECOND_PROVIDER_ID, "VERIFIED");
        addCriteria(FIRST_PROVIDER_ID, "COURT", "BGC");
        addCriteria(SECOND_PROVIDER_ID, "COURT", "BGC");

        assertThat(matchingAccess.selectRecipients(new MatchRequestRecipientsCommand("COURT", "BGC")))
                .isInstanceOf(RequestRecipientSelection.Candidates.class);

        insertProvider(THIRD_PROVIDER_ID, "VERIFIED");
        addCriteria(THIRD_PROVIDER_ID, "COURT", "BGC");

        assertThat(matchingAccess.selectRecipients(new MatchRequestRecipientsCommand("COURT", "BGC")))
                .isEqualTo(new RequestRecipientSelection.CapExceeded(2));
    }

    @Test
    void retainsPostgreSqlUuidOrderAcrossTheSignedComparisonBoundary() {
        insertProvider(FIRST_UPPER_HALF_PROVIDER_ID, "VERIFIED");
        insertProvider(LAST_LOWER_HALF_PROVIDER_ID, "VERIFIED");
        addCriteria(FIRST_UPPER_HALF_PROVIDER_ID, "COURT", "BGC");
        addCriteria(LAST_LOWER_HALF_PROVIDER_ID, "COURT", "BGC");

        assertThat(matchingAccess.selectRecipients(new MatchRequestRecipientsCommand("COURT", "BGC")))
                .isEqualTo(new RequestRecipientSelection.Candidates(java.util.List.of(
                        new RequestRecipientCandidate(LAST_LOWER_HALF_PROVIDER_ID, 1),
                        new RequestRecipientCandidate(FIRST_UPPER_HALF_PROVIDER_ID, 1))));
    }

    private void insertProvider(UUID providerId, String verificationStatus) {
        jdbcClient.sql("""
                        INSERT INTO provider_organization (
                            provider_id, display_name, status, verification_status, version, eligibility_version
                        ) VALUES (:providerId, :displayName, 'ACTIVE', :verificationStatus, 1, 1)
                        """)
                .param("providerId", providerId)
                .param("displayName", "Provider " + providerId)
                .param("verificationStatus", verificationStatus)
                .update();
    }

    private void addCriteria(UUID providerId, String category, String serviceAreaCode) {
        jdbcClient.sql("INSERT INTO provider_supported_category (provider_id, category) VALUES (:providerId, :category)")
                .param("providerId", providerId)
                .param("category", category)
                .update();
        jdbcClient.sql("INSERT INTO provider_service_area (provider_id, area_code) VALUES (:providerId, :serviceAreaCode)")
                .param("providerId", providerId)
                .param("serviceAreaCode", serviceAreaCode)
                .update();
    }
}
