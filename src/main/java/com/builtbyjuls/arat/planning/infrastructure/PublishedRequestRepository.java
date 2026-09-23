package com.builtbyjuls.arat.planning.infrastructure;

import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.planning.domain.PublishedRequest;
import com.builtbyjuls.arat.planning.domain.PublishedRequestState;
import com.builtbyjuls.arat.planning.domain.RequestDistributionMode;
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
public class PublishedRequestRepository {

    public record RequestOwner(UUID requestId, UUID planId, UUID groupId) {
    }

    public record RequestObservation(PublishedRequest request, boolean actionable) {
    }

    private final JdbcClient jdbcClient;

    public PublishedRequestRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PublishedRequest insert(PublishedRequest request) {
        return jdbcClient.sql("""
                        INSERT INTO planning_published_request (
                            request_id, plan_id, request_version, state, distribution_mode,
                            category, time_zone, area_code, radius_km, requested_starts_at,
                            requested_ends_at, minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                            provider_safe_notes, category_attributes, offer_deadline,
                            published_by_account_id, published_at, closed_at
                        )
                        VALUES (
                            :requestId, :planId, :requestVersion, :state, :distributionMode,
                            :category, :timeZone, :areaCode, :radiusKm, :requestedStartsAt,
                            :requestedEndsAt, :minimumHeadcount, :maximumHeadcount, :budgetCurrency,
                            :budgetMinimumMinorUnits, :budgetMaximumMinorUnits, CAST(:mustHaves AS varchar[]),
                            :providerSafeNotes, CAST(:categoryAttributes AS jsonb), :offerDeadline,
                            :publishedByAccountId, :publishedAt, :closedAt
                        )
                        RETURNING request_id, plan_id, request_version, state, distribution_mode,
                                  category, time_zone, area_code, radius_km, requested_starts_at,
                                  requested_ends_at, minimum_headcount, maximum_headcount,
                                  budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                                  provider_safe_notes, category_attributes::text AS category_attributes,
                                  offer_deadline, published_by_account_id, published_at, closed_at
                        """)
                .param("requestId", request.requestId())
                .param("planId", request.planId())
                .param("requestVersion", request.requestVersion())
                .param("state", request.state().name())
                .param("distributionMode", request.distributionMode().name())
                .param("category", request.category().name())
                .param("timeZone", request.timeZone())
                .param("areaCode", request.areaCode())
                .param("radiusKm", request.radiusKm())
                .param("requestedStartsAt", request.requestedStartsAt())
                .param("requestedEndsAt", request.requestedEndsAt())
                .param("minimumHeadcount", request.minimumHeadcount())
                .param("maximumHeadcount", request.maximumHeadcount())
                .param("budgetCurrency", request.budgetMinimumMinorUnits() == null ? null : "PHP")
                .param("budgetMinimumMinorUnits", request.budgetMinimumMinorUnits())
                .param("budgetMaximumMinorUnits", request.budgetMaximumMinorUnits())
                .param("mustHaves", request.mustHaves().toArray(String[]::new))
                .param("providerSafeNotes", request.providerSafeNotes())
                .param("categoryAttributes", request.categoryAttributes())
                .param("offerDeadline", request.offerDeadline())
                .param("publishedByAccountId", request.publishedByAccountId())
                .param("publishedAt", request.publishedAt())
                .param("closedAt", request.closedAt())
                .query(this::mapRequest)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<PublishedRequest> findById(UUID requestId) {
        return jdbcClient.sql(selectRequest() + " WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(this::mapRequest)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<PublishedRequest> findCurrentByPlanId(UUID planId) {
        return jdbcClient.sql(selectRequest() + " WHERE plan_id = :planId AND state = 'OPEN'")
                .param("planId", planId)
                .query(this::mapRequest)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<RequestOwner> findOwner(UUID requestId) {
        return jdbcClient.sql("""
                        SELECT r.request_id, r.plan_id, p.group_id
                        FROM planning_published_request r
                        JOIN planning_plan p ON p.plan_id = r.plan_id
                        WHERE r.request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNum) -> new RequestOwner(
                        resultSet.getObject("request_id", UUID.class),
                        resultSet.getObject("plan_id", UUID.class),
                        resultSet.getObject("group_id", UUID.class)))
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PublishedRequest> lockById(UUID requestId) {
        return jdbcClient.sql(selectRequest() + " WHERE request_id = :requestId FOR UPDATE")
                .param("requestId", requestId)
                .query(this::mapRequest)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long allocateNextVersion(UUID planId) {
        return jdbcClient.sql("""
                        SELECT coalesce(max(request_version), 0) + 1
                        FROM planning_published_request
                        WHERE plan_id = :planId
                        """)
                .param("planId", planId)
                .query(Long.class)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PublishedRequest> transitionOpen(
            UUID requestId, UUID planId, PublishedRequestState terminalState, OffsetDateTime closedAt) {
        if (terminalState == PublishedRequestState.OPEN) {
            throw new IllegalArgumentException("terminalState must be terminal");
        }
        return jdbcClient.sql("""
                        UPDATE planning_published_request
                        SET state = :terminalState,
                            closed_at = :closedAt
                        WHERE request_id = :requestId
                          AND plan_id = :planId
                          AND state = 'OPEN'
                        RETURNING request_id, plan_id, request_version, state, distribution_mode,
                                  category, time_zone, area_code, radius_km, requested_starts_at,
                                  requested_ends_at, minimum_headcount, maximum_headcount,
                                  budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                                  provider_safe_notes, category_attributes::text AS category_attributes,
                                  offer_deadline, published_by_account_id, published_at, closed_at
                        """)
                .param("terminalState", terminalState.name())
                .param("closedAt", closedAt)
                .param("requestId", requestId)
                .param("planId", planId)
                .query(this::mapRequest)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OffsetDateTime databaseDecisionTime() {
        return jdbcClient.sql("SELECT clock_timestamp()")
                .query(OffsetDateTime.class)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<RequestObservation> findObservationById(UUID requestId) {
        return jdbcClient.sql("""
                        SELECT r.request_id, r.plan_id, r.request_version, r.state, r.distribution_mode,
                               r.category, r.time_zone, r.area_code, r.radius_km, r.requested_starts_at,
                               r.requested_ends_at, r.minimum_headcount, r.maximum_headcount,
                               r.budget_minimum_minor_units, r.budget_maximum_minor_units, r.must_haves,
                               r.provider_safe_notes, r.category_attributes::text AS category_attributes,
                               r.offer_deadline, r.published_by_account_id, r.published_at, r.closed_at,
                               (r.state = 'OPEN'
                                AND p.state = 'OPEN_FOR_OFFERS'
                                AND p.current_request_id = r.request_id
                                AND clock_timestamp() < r.offer_deadline) AS actionable
                        FROM planning_published_request r
                        JOIN planning_plan p ON p.plan_id = r.plan_id
                        WHERE r.request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNum) -> new RequestObservation(
                        mapRequest(resultSet, rowNum), resultSet.getBoolean("actionable")))
                .optional();
    }

    private String selectRequest() {
        return """
                SELECT request_id, plan_id, request_version, state, distribution_mode,
                       category, time_zone, area_code, radius_km, requested_starts_at,
                       requested_ends_at, minimum_headcount, maximum_headcount,
                       budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                       provider_safe_notes, category_attributes::text AS category_attributes,
                       offer_deadline, published_by_account_id, published_at, closed_at
                FROM planning_published_request
                """;
    }

    private PublishedRequest mapRequest(ResultSet resultSet, int rowNum) throws SQLException {
        return new PublishedRequest(
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("plan_id", UUID.class),
                resultSet.getLong("request_version"),
                PublishedRequestState.valueOf(resultSet.getString("state")),
                RequestDistributionMode.valueOf(resultSet.getString("distribution_mode")),
                ActivityCategory.valueOf(resultSet.getString("category")),
                resultSet.getString("time_zone"),
                resultSet.getString("area_code"),
                resultSet.getInt("radius_km"),
                resultSet.getObject("requested_starts_at", OffsetDateTime.class),
                resultSet.getObject("requested_ends_at", OffsetDateTime.class),
                resultSet.getInt("minimum_headcount"),
                resultSet.getInt("maximum_headcount"),
                resultSet.getObject("budget_minimum_minor_units", Long.class),
                resultSet.getObject("budget_maximum_minor_units", Long.class),
                array(resultSet, "must_haves", String.class),
                resultSet.getString("provider_safe_notes"),
                resultSet.getString("category_attributes"),
                resultSet.getObject("offer_deadline", OffsetDateTime.class),
                resultSet.getObject("published_by_account_id", UUID.class),
                resultSet.getObject("published_at", OffsetDateTime.class),
                resultSet.getObject("closed_at", OffsetDateTime.class));
    }

    private <T> List<T> array(ResultSet resultSet, String column, Class<T> type) throws SQLException {
        return Arrays.stream((Object[]) resultSet.getArray(column).getArray()).map(type::cast).toList();
    }
}
