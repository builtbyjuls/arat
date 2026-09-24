package com.builtbyjuls.arat.providers.infrastructure;

import com.builtbyjuls.arat.providers.api.ProviderEligibilityCandidate;
import com.builtbyjuls.arat.providers.api.ProviderEligibilityCriteria;
import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import com.builtbyjuls.arat.providers.domain.ProviderOrganizationStatus;
import com.builtbyjuls.arat.providers.domain.ProviderStaffMembership;
import com.builtbyjuls.arat.providers.domain.ProviderStaffRole;
import com.builtbyjuls.arat.providers.domain.ProviderStaffStatus;
import com.builtbyjuls.arat.providers.domain.ProviderSuspension;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationDecision;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationDecisionRecord;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationSubmission;
import com.builtbyjuls.arat.providers.domain.ProviderWorkspace;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProviderRepository {

    private final JdbcClient jdbcClient;

    public ProviderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(
            ProviderOrganization organization,
            List<ProviderStaffMembership> staffMemberships,
            List<ProviderCategory> supportedCategories,
            List<String> serviceAreaCodes) {
        validateChildren(organization.providerId(), staffMemberships, supportedCategories, serviceAreaCodes);
        jdbcClient.sql("""
                        INSERT INTO provider_organization (
                            provider_id, display_name, status, verification_status,
                            version, eligibility_version, created_at, updated_at
                        )
                        VALUES (
                            :providerId, :displayName, :status, :verificationStatus,
                            :version, :eligibilityVersion, :createdAt, :updatedAt
                        )
                        """)
                .param("providerId", organization.providerId())
                .param("displayName", organization.displayName())
                .param("status", organization.status().name())
                .param("verificationStatus", organization.verificationStatus().name())
                .param("version", organization.version())
                .param("eligibilityVersion", organization.eligibilityVersion())
                .param("createdAt", organization.createdAt())
                .param("updatedAt", organization.updatedAt())
                .update();
        staffMemberships.forEach(this::insertStaffMembership);
        supportedCategories.forEach(category -> insertSupportedCategory(organization.providerId(), category));
        serviceAreaCodes.forEach(areaCode -> insertServiceArea(organization.providerId(), areaCode));
    }

    @Transactional(readOnly = true)
    public Optional<ProviderOrganization> findById(UUID providerId) {
        return jdbcClient.sql("""
                        SELECT provider_id, display_name, status, verification_status,
                               version, eligibility_version, created_at, updated_at
                        FROM provider_organization
                        WHERE provider_id = :providerId
                        """)
                .param("providerId", providerId)
                .query(this::mapOrganization)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<ProviderStaffMembership> findStaffMembership(UUID providerId, UUID accountId) {
        return jdbcClient.sql("""
                        SELECT provider_id, account_id, role, status, joined_at, removed_at
                        FROM provider_staff_membership
                        WHERE provider_id = :providerId
                          AND account_id = :accountId
                        """)
                .param("providerId", providerId)
                .param("accountId", accountId)
                .query(this::mapStaffMembership)
                .optional();
    }

    @Transactional(readOnly = true)
    public List<ProviderWorkspace> listActiveStaffMembershipPage(UUID accountId, int limit) {
        return jdbcClient.sql("""
                        SELECT organization.provider_id, organization.display_name, organization.status,
                               organization.verification_status, organization.version,
                               organization.eligibility_version, organization.created_at,
                               organization.updated_at, membership.role,
                               ARRAY(
                                   SELECT category
                                   FROM provider_supported_category
                                   WHERE provider_id = organization.provider_id
                                   ORDER BY category
                               ) AS supported_categories,
                               ARRAY(
                                   SELECT area_code
                                   FROM provider_service_area
                                   WHERE provider_id = organization.provider_id
                                   ORDER BY area_code
                               ) AS service_area_codes
                        FROM provider_staff_membership membership
                        JOIN provider_organization organization ON organization.provider_id = membership.provider_id
                        WHERE membership.account_id = :accountId
                          AND membership.status = 'ACTIVE'
                        ORDER BY organization.created_at DESC, organization.provider_id DESC
                        LIMIT :limit
                        """)
                .param("accountId", accountId)
                .param("limit", limit)
                .query((resultSet, rowNum) -> new ProviderWorkspace(
                        mapOrganization(resultSet, rowNum),
                        ProviderStaffRole.valueOf(resultSet.getString("role")),
                        textArray(resultSet, "supported_categories").stream()
                                .map(ProviderCategory::valueOf)
                                .toList(),
                        textArray(resultSet, "service_area_codes")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<ProviderWorkspace> listActiveStaffMembershipPage(
            UUID accountId, OffsetDateTime createdAt, UUID providerId, int limit) {
        return jdbcClient.sql("""
                        SELECT organization.provider_id, organization.display_name, organization.status,
                               organization.verification_status, organization.version,
                               organization.eligibility_version, organization.created_at,
                               organization.updated_at, membership.role,
                               ARRAY(
                                   SELECT category
                                   FROM provider_supported_category
                                   WHERE provider_id = organization.provider_id
                                   ORDER BY category
                               ) AS supported_categories,
                               ARRAY(
                                   SELECT area_code
                                   FROM provider_service_area
                                   WHERE provider_id = organization.provider_id
                                   ORDER BY area_code
                               ) AS service_area_codes
                        FROM provider_staff_membership membership
                        JOIN provider_organization organization ON organization.provider_id = membership.provider_id
                        WHERE membership.account_id = :accountId
                          AND membership.status = 'ACTIVE'
                          AND (organization.created_at, organization.provider_id) < (:createdAt, :providerId)
                        ORDER BY organization.created_at DESC, organization.provider_id DESC
                        LIMIT :limit
                        """)
                .param("accountId", accountId)
                .param("createdAt", createdAt)
                .param("providerId", providerId)
                .param("limit", limit)
                .query((resultSet, rowNum) -> new ProviderWorkspace(
                        mapOrganization(resultSet, rowNum),
                        ProviderStaffRole.valueOf(resultSet.getString("role")),
                        textArray(resultSet, "supported_categories").stream()
                                .map(ProviderCategory::valueOf)
                                .toList(),
                        textArray(resultSet, "service_area_codes")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<ProviderCategory> findSupportedCategories(UUID providerId) {
        return jdbcClient.sql("""
                        SELECT category
                        FROM provider_supported_category
                        WHERE provider_id = :providerId
                        ORDER BY category
                        """)
                .param("providerId", providerId)
                .query((resultSet, rowNum) -> ProviderCategory.valueOf(resultSet.getString("category")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<String> findServiceAreaCodes(UUID providerId) {
        return jdbcClient.sql("""
                        SELECT area_code
                        FROM provider_service_area
                        WHERE provider_id = :providerId
                        ORDER BY area_code
                        """)
                .param("providerId", providerId)
                .query((resultSet, rowNum) -> resultSet.getString("area_code"))
                .list();
    }

    @Transactional(readOnly = true)
    public List<ProviderEligibilityCandidate> findVerifiedEligibilityCandidates(
            ProviderEligibilityCriteria criteria, int maximumCandidateCount) {
        return jdbcClient.sql("""
                        SELECT DISTINCT organization.provider_id, organization.eligibility_version
                        FROM provider_organization AS organization
                        JOIN provider_supported_category AS category
                          ON category.provider_id = organization.provider_id
                         AND category.category = :category
                        JOIN provider_service_area AS service_area
                          ON service_area.provider_id = organization.provider_id
                         AND service_area.area_code = :serviceAreaCode
                        WHERE organization.status = 'ACTIVE'
                          AND organization.verification_status = 'VERIFIED'
                        ORDER BY organization.provider_id ASC
                        LIMIT :maximumCandidateCount
                        """)
                .param("category", criteria.category())
                .param("serviceAreaCode", criteria.serviceAreaCode())
                .param("maximumCandidateCount", maximumCandidateCount)
                .query((resultSet, rowNum) -> new ProviderEligibilityCandidate(
                        resultSet.getObject("provider_id", UUID.class),
                        resultSet.getLong("eligibility_version")))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProviderOrganization lockOrganization(UUID providerId) {
        return findAndLockOrganization(providerId).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProviderOrganization> findAndLockOrganization(UUID providerId) {
        return jdbcClient.sql("""
                        SELECT provider_id, display_name, status, verification_status,
                               version, eligibility_version, created_at, updated_at
                        FROM provider_organization
                        WHERE provider_id = :providerId
                        FOR NO KEY UPDATE
                """)
                .param("providerId", providerId)
                .query(this::mapOrganization)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProviderOrganization> replaceProfileVersioned(
            UUID providerId,
            long expectedVersion,
            String displayName,
            List<ProviderCategory> supportedCategories,
            List<String> serviceAreaCodes) {
        var organization = jdbcClient.sql("""
                        UPDATE provider_organization
                        SET display_name = :displayName,
                            version = version + 1,
                            updated_at = statement_timestamp()
                        WHERE provider_id = :providerId
                          AND version = :expectedVersion
                        RETURNING provider_id, display_name, status, verification_status,
                                  version, eligibility_version, created_at, updated_at
                        """)
                .param("providerId", providerId)
                .param("expectedVersion", expectedVersion)
                .param("displayName", displayName)
                .query(this::mapOrganization)
                .optional();
        if (organization.isEmpty()) {
            return organization;
        }
        jdbcClient.sql("DELETE FROM provider_supported_category WHERE provider_id = :providerId")
                .param("providerId", providerId)
                .update();
        jdbcClient.sql("DELETE FROM provider_service_area WHERE provider_id = :providerId")
                .param("providerId", providerId)
                .update();
        supportedCategories.forEach(category -> insertSupportedCategory(providerId, category));
        serviceAreaCodes.forEach(areaCode -> insertServiceArea(providerId, areaCode));
        return organization;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProviderOrganization> transitionVerificationToPending(
            UUID providerId, long expectedVersion, UUID submissionId) {
        return jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = 'PENDING',
                            current_verification_submission_id = :submissionId,
                            version = version + 1,
                            eligibility_version = eligibility_version + 1,
                            updated_at = statement_timestamp()
                        WHERE provider_id = :providerId
                          AND version = :expectedVersion
                          AND verification_status IN ('UNVERIFIED', 'REJECTED')
                        RETURNING provider_id, display_name, status, verification_status,
                                  version, eligibility_version, created_at, updated_at
                        """)
                .param("providerId", providerId)
                .param("expectedVersion", expectedVersion)
                .param("submissionId", submissionId)
                .query(this::mapOrganization)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertVerificationSubmission(ProviderVerificationSubmission submission) {
        jdbcClient.sql("""
                        INSERT INTO provider_verification_submission (
                            submission_id, provider_id, submitted_by_account_id, evidence_count
                        )
                        VALUES (:submissionId, :providerId, :submittedByAccountId, :evidenceCount)
                        """)
                .param("submissionId", submission.submissionId())
                .param("providerId", submission.providerId())
                .param("submittedByAccountId", submission.submittedByAccountId())
                .param("evidenceCount", submission.evidenceReferences().size())
                .update();
        for (var index = 0; index < submission.evidenceReferences().size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO provider_verification_evidence_reference (
                                submission_id, sort_order, evidence_reference
                            )
                            VALUES (:submissionId, :sortOrder, :evidenceReference)
                            """)
                    .param("submissionId", submission.submissionId())
                    .param("sortOrder", index + 1)
                    .param("evidenceReference", submission.evidenceReferences().get(index))
                    .update();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> findCurrentVerificationSubmissionId(UUID providerId) {
        return jdbcClient.sql("""
                        SELECT current_verification_submission_id
                        FROM provider_organization
                        WHERE provider_id = :providerId
                        """)
                .param("providerId", providerId)
                .query(UUID.class)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProviderOrganization> transitionPendingVerification(
            UUID providerId, long expectedVersion, ProviderVerificationDecision decision) {
        var targetStatus = decision == ProviderVerificationDecision.ACCEPT ? "VERIFIED" : "REJECTED";
        return jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = :targetStatus,
                            current_verification_submission_id = NULL,
                            version = version + 1,
                            eligibility_version = eligibility_version + 1,
                            updated_at = statement_timestamp()
                        WHERE provider_id = :providerId
                          AND version = :expectedVersion
                          AND verification_status = 'PENDING'
                        RETURNING provider_id, display_name, status, verification_status,
                                  version, eligibility_version, created_at, updated_at
                        """)
                .param("providerId", providerId)
                .param("expectedVersion", expectedVersion)
                .param("targetStatus", targetStatus)
                .query(this::mapOrganization)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertVerificationDecision(ProviderVerificationDecisionRecord decision) {
        jdbcClient.sql("""
                        INSERT INTO provider_verification_decision (
                            decision_id, provider_id, submission_id, decided_by_account_id,
                            decision, decision_note
                        )
                        VALUES (
                            :decisionId, :providerId, :submissionId, :decidedByAccountId,
                            :decision, :decisionNote
                        )
                        """)
                .param("decisionId", decision.decisionId())
                .param("providerId", decision.providerId())
                .param("submissionId", decision.submissionId())
                .param("decidedByAccountId", decision.decidedByAccountId())
                .param("decision", decision.decision().name())
                .param("decisionNote", decision.note())
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProviderOrganization> suspendVerifiedProvider(UUID providerId, long expectedVersion) {
        return jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = 'SUSPENDED',
                            version = version + 1,
                            eligibility_version = eligibility_version + 1,
                            updated_at = statement_timestamp()
                        WHERE provider_id = :providerId
                          AND version = :expectedVersion
                          AND verification_status = 'VERIFIED'
                        RETURNING provider_id, display_name, status, verification_status,
                                  version, eligibility_version, created_at, updated_at
                        """)
                .param("providerId", providerId)
                .param("expectedVersion", expectedVersion)
                .query(this::mapOrganization)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProviderOrganization> restoreSuspendedProvider(UUID providerId, long expectedVersion) {
        return jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = 'VERIFIED',
                            version = version + 1,
                            eligibility_version = eligibility_version + 1,
                            updated_at = statement_timestamp()
                        WHERE provider_id = :providerId
                          AND version = :expectedVersion
                          AND verification_status = 'SUSPENDED'
                        RETURNING provider_id, display_name, status, verification_status,
                                  version, eligibility_version, created_at, updated_at
                        """)
                .param("providerId", providerId)
                .param("expectedVersion", expectedVersion)
                .query(this::mapOrganization)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertSuspension(ProviderSuspension suspension) {
        jdbcClient.sql("""
                        INSERT INTO provider_suspension (
                            suspension_id, provider_id, suspended_by_account_id, suspension_reason
                        )
                        VALUES (:suspensionId, :providerId, :suspendedByAccountId, :suspensionReason)
                        """)
                .param("suspensionId", suspension.suspensionId())
                .param("providerId", suspension.providerId())
                .param("suspendedByAccountId", suspension.suspendedByAccountId())
                .param("suspensionReason", suspension.reason())
                .update();
    }

    private void insertStaffMembership(ProviderStaffMembership membership) {
        jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (
                            provider_id, account_id, role, status, joined_at, removed_at
                        )
                        VALUES (:providerId, :accountId, :role, :status, :joinedAt, :removedAt)
                        """)
                .param("providerId", membership.providerId())
                .param("accountId", membership.accountId())
                .param("role", membership.role().name())
                .param("status", membership.status().name())
                .param("joinedAt", membership.joinedAt())
                .param("removedAt", membership.removedAt())
                .update();
    }

    private void insertSupportedCategory(UUID providerId, ProviderCategory category) {
        jdbcClient.sql("""
                        INSERT INTO provider_supported_category (provider_id, category)
                        VALUES (:providerId, :category)
                        """)
                .param("providerId", providerId)
                .param("category", category.name())
                .update();
    }

    private void insertServiceArea(UUID providerId, String areaCode) {
        jdbcClient.sql("""
                        INSERT INTO provider_service_area (provider_id, area_code)
                        VALUES (:providerId, :areaCode)
                        """)
                .param("providerId", providerId)
                .param("areaCode", areaCode)
                .update();
    }

    private ProviderOrganization mapOrganization(ResultSet resultSet, int rowNum) throws SQLException {
        return new ProviderOrganization(
                resultSet.getObject("provider_id", UUID.class),
                resultSet.getString("display_name"),
                ProviderOrganizationStatus.valueOf(resultSet.getString("status")),
                ProviderVerificationStatus.valueOf(resultSet.getString("verification_status")),
                resultSet.getLong("version"),
                resultSet.getLong("eligibility_version"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    private ProviderStaffMembership mapStaffMembership(ResultSet resultSet, int rowNum) throws SQLException {
        return new ProviderStaffMembership(
                resultSet.getObject("provider_id", UUID.class),
                resultSet.getObject("account_id", UUID.class),
                ProviderStaffRole.valueOf(resultSet.getString("role")),
                ProviderStaffStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("joined_at", OffsetDateTime.class),
                resultSet.getObject("removed_at", OffsetDateTime.class));
    }

    private List<String> textArray(ResultSet resultSet, String column) throws SQLException {
        var array = resultSet.getArray(column);
        try {
            return Arrays.stream((Object[]) array.getArray()).map(String.class::cast).toList();
        } finally {
            array.free();
        }
    }

    private void validateChildren(
            UUID providerId,
            List<ProviderStaffMembership> staffMemberships,
            List<ProviderCategory> supportedCategories,
            List<String> serviceAreaCodes) {
        if (staffMemberships == null || supportedCategories == null || serviceAreaCodes == null) {
            throw new IllegalArgumentException("provider children must not be null");
        }
        if (supportedCategories.size() > 10 || serviceAreaCodes.size() > 20) {
            throw new IllegalArgumentException("provider capability limits exceeded");
        }
        if (staffMemberships.stream().anyMatch(membership -> !providerId.equals(membership.providerId()))) {
            throw new IllegalArgumentException("staff membership must belong to the provider");
        }
    }
}
