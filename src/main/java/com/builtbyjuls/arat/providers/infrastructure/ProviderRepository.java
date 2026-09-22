package com.builtbyjuls.arat.providers.infrastructure;

import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import com.builtbyjuls.arat.providers.domain.ProviderOrganizationStatus;
import com.builtbyjuls.arat.providers.domain.ProviderStaffMembership;
import com.builtbyjuls.arat.providers.domain.ProviderStaffRole;
import com.builtbyjuls.arat.providers.domain.ProviderStaffStatus;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationSubmission;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
    public Optional<ProviderOrganization> transitionVerificationToPending(UUID providerId, long expectedVersion) {
        return jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = 'PENDING',
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
