package com.builtbyjuls.arat.marketplace.infrastructure;

import com.builtbyjuls.arat.marketplace.domain.RequestRecipient;
import com.builtbyjuls.arat.marketplace.domain.RequestRecipientAccessState;
import com.builtbyjuls.arat.marketplace.domain.RequestRecipientSource;
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
public class RequestRecipientRepository {

    private final JdbcClient jdbcClient;

    public RequestRecipientRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequestRecipient insert(RequestRecipient recipient) {
        return jdbcClient.sql("""
                        INSERT INTO marketplace_request_recipient (
                            published_request_id, provider_id, provider_eligibility_version,
                            source, source_listing_id, access_state, created_at
                        )
                        VALUES (
                            :publishedRequestId, :providerId, :providerEligibilityVersion,
                            :source, :sourceListingId, :accessState, :createdAt
                        )
                        RETURNING published_request_id, provider_id, provider_eligibility_version,
                                  source, source_listing_id, access_state, created_at
                        """)
                .param("publishedRequestId", recipient.publishedRequestId())
                .param("providerId", recipient.providerId())
                .param("providerEligibilityVersion", recipient.providerEligibilityVersion())
                .param("source", recipient.source().name())
                .param("sourceListingId", recipient.sourceListingId())
                .param("accessState", recipient.accessState().name())
                .param("createdAt", recipient.createdAt())
                .query(this::mapRecipient)
                .single();
    }

    @Transactional(readOnly = true)
    public List<RequestRecipient> findByRequestId(UUID publishedRequestId) {
        return jdbcClient.sql(selectRecipients() + """
                        WHERE published_request_id = :publishedRequestId
                        ORDER BY created_at DESC, provider_id ASC
                        """)
                .param("publishedRequestId", publishedRequestId)
                .query(this::mapRecipient)
                .list();
    }

    @Transactional(readOnly = true)
    public List<UUID> findProviderIdsByRequestId(UUID publishedRequestId) {
        return jdbcClient.sql("""
                        SELECT provider_id
                        FROM marketplace_request_recipient
                        WHERE published_request_id = :publishedRequestId
                        ORDER BY provider_id
                        """)
                .param("publishedRequestId", publishedRequestId)
                .query(UUID.class)
                .list();
    }

    @Transactional(readOnly = true)
    public List<RequestRecipient> findActiveByProviderId(UUID providerId) {
        return jdbcClient.sql(selectRecipients() + """
                        WHERE provider_id = :providerId
                          AND access_state = 'ACTIVE'
                        ORDER BY created_at DESC, published_request_id DESC
                        """)
                .param("providerId", providerId)
                .query(this::mapRecipient)
                .list();
    }

    @Transactional(readOnly = true)
    public List<RequestRecipient> findActiveProviderFeedPage(
            UUID providerId,
            long eligibilityVersion,
            OffsetDateTime beforeCreatedAt,
            UUID beforeRequestId,
            int limit) {
        var cursorPredicate = beforeCreatedAt == null ? "" : """
                          AND (created_at, published_request_id) < (:beforeCreatedAt, :beforeRequestId)
                        """;
        var query = jdbcClient.sql(selectRecipients() + """
                        WHERE provider_id = :providerId
                          AND provider_eligibility_version = :eligibilityVersion
                          AND access_state = 'ACTIVE'
                        """ + cursorPredicate + """
                        ORDER BY created_at DESC, published_request_id DESC
                        LIMIT :limit
                        """)
                .param("providerId", providerId)
                .param("eligibilityVersion", eligibilityVersion)
                .param("limit", limit);
        if (beforeCreatedAt != null) {
            query.param("beforeCreatedAt", beforeCreatedAt)
                    .param("beforeRequestId", beforeRequestId);
        }
        return query.query(this::mapRecipient).list();
    }

    @Transactional(readOnly = true)
    public Optional<RequestRecipient> findActiveByRequestIdAndProviderId(UUID publishedRequestId, UUID providerId) {
        return jdbcClient.sql(selectRecipients() + """
                        WHERE published_request_id = :publishedRequestId
                          AND provider_id = :providerId
                          AND access_state = 'ACTIVE'
                        """)
                .param("publishedRequestId", publishedRequestId)
                .param("providerId", providerId)
                .query(this::mapRecipient)
                .optional();
    }

    private String selectRecipients() {
        return """
                SELECT published_request_id, provider_id, provider_eligibility_version,
                       source, source_listing_id, access_state, created_at
                FROM marketplace_request_recipient
                """;
    }

    private RequestRecipient mapRecipient(ResultSet resultSet, int rowNum) throws SQLException {
        return new RequestRecipient(
                resultSet.getObject("published_request_id", UUID.class),
                resultSet.getObject("provider_id", UUID.class),
                resultSet.getLong("provider_eligibility_version"),
                RequestRecipientSource.valueOf(resultSet.getString("source")),
                resultSet.getObject("source_listing_id", UUID.class),
                RequestRecipientAccessState.valueOf(resultSet.getString("access_state")),
                resultSet.getObject("created_at", OffsetDateTime.class));
    }
}
