package com.builtbyjuls.arat.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.PlatformRole;
import com.builtbyjuls.arat.marketplace.api.ProviderPublishedRequestDetailException;
import com.builtbyjuls.arat.marketplace.api.ProviderRequestFeedException;
import com.builtbyjuls.arat.marketplace.api.PublishRequestCommand;
import com.builtbyjuls.arat.marketplace.api.RequestPublicationResponse;
import com.builtbyjuls.arat.providers.api.ReplaceProviderProfileCommand;
import com.builtbyjuls.arat.providers.api.RestoreProviderCommand;
import com.builtbyjuls.arat.providers.api.SuspendProviderCommand;
import com.builtbyjuls.arat.providers.application.ProviderProfileService;
import com.builtbyjuls.arat.providers.application.ProviderRestorationService;
import com.builtbyjuls.arat.providers.application.ProviderSuspensionService;
import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EligibilityFencingRaceIT extends PostgreSqlIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Autowired private JdbcClient jdbcClient;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RequestPublicationService publicationService;
    @Autowired private ProviderSuspensionService suspensionService;
    @Autowired private ProviderRestorationService restorationService;
    @Autowired private ProviderProfileService profileService;
    @Autowired private ProviderPublishedRequestDetailService detailService;
    @Autowired private ProviderRequestFeedService feedService;

    @Test
    void suspensionAfterMatchingFencesTheStaleRecipientAcrossRestorationAndReplacement() throws Exception {
        var fixture = fixture(false);
        var executor = workerExecutor();
        Future<RequestPublicationResponse> publication = null;
        RequestPublicationResponse published;

        try (var insertBlocker = dataSource.getConnection()) {
            insertBlocker.setAutoCommit(false);
            configureConnection(insertBlocker, "eligibility-recipient-insert-blocker");
            lockRecipientTableInShareMode(insertBlocker);

            publication = submit(
                    executor,
                    "eligibility-publish-before-suspension",
                    () -> publicationService.publish(publishCommand(
                            fixture, fixture.finalizationId(), 1, "publish-before-suspension")));
            awaitLockWait("eligibility-publish-before-suspension");

            var suspension = suspensionService.suspend(suspensionCommand(fixture, "suspend-after-match"));
            assertThat(suspension.eligibilityVersion()).isEqualTo(3);
            assertProvider(fixture, "SUSPENDED", 3, 3);

            insertBlocker.rollback();
            published = get("publication after suspension", publication);
        } finally {
            cancel(publication);
            shutdown(executor);
        }

        var staleRequestId = published.request().requestId();
        assertRecipient(staleRequestId, fixture.targetProviderId(), 2);
        assertSuspendedPrivacy(fixture, staleRequestId);

        var restoration = restorationService.restore(restorationCommand(fixture, "restore-after-stale-grant"));
        assertThat(restoration.eligibilityVersion()).isEqualTo(4);
        assertProvider(fixture, "VERIFIED", 4, 4);
        assertRestoredStalePrivacy(fixture, staleRequestId);

        var replacementFinalizationId = insertFinalization(fixture, 2);
        var replacement = publicationService.publish(publishCommand(
                fixture, replacementFinalizationId, 2, "publish-after-restoration"));
        var freshRequestId = replacement.request().requestId();

        assertRecipient(staleRequestId, fixture.targetProviderId(), 2);
        assertRecipient(freshRequestId, fixture.targetProviderId(), 4);
        assertThat(recipientCount(fixture.targetProviderId(), 4)).isOne();
        assertThat(detailService.find(fixture.targetProviderId(), freshRequestId, fixture.staffId()).requestId())
                .isEqualTo(freshRequestId);
        assertFeedContainsExactly(fixture, freshRequestId);
    }

    @Test
    void publicationCompletesThroughTheCompatibleForeignKeyLockBeforeSuspension() throws Exception {
        var fixture = fixture(false);
        var executor = workerExecutor();
        Future<RequestPublicationResponse> publication = null;
        Future<?> suspension = null;
        RequestPublicationResponse published;

        try (var providerLockOwner = dataSource.getConnection()) {
            providerLockOwner.setAutoCommit(false);
            configureConnection(providerLockOwner, "eligibility-provider-lock-owner");
            lockProviderForNoKeyUpdate(providerLockOwner, fixture.targetProviderId());

            publication = submit(
                    executor,
                    "eligibility-publish-with-provider-lock",
                    () -> publicationService.publish(publishCommand(
                            fixture, fixture.finalizationId(), 1, "publish-before-suspension-lock")));
            published = get("publication through recipient foreign key lock", publication);
            assertRecipient(published.request().requestId(), fixture.targetProviderId(), 2);

            suspension = submit(
                    executor,
                    "eligibility-suspend-after-publication",
                    () -> suspensionService.suspend(suspensionCommand(fixture, "suspend-after-publication")));
            awaitLockWait("eligibility-suspend-after-publication");

            providerLockOwner.rollback();
            get("suspension after publication", suspension);
        } finally {
            cancel(publication);
            cancel(suspension);
            shutdown(executor);
        }

        assertProvider(fixture, "SUSPENDED", 3, 3);
        assertSuspendedPrivacy(fixture, published.request().requestId());
    }

    @Test
    void profileReplacementChangesOnlyTheAudienceObservedByMatching() throws Exception {
        var matchingFirst = fixture(true);
        var matchingFirstExecutor = workerExecutor();
        Future<RequestPublicationResponse> matchingFirstPublication = null;
        RequestPublicationResponse observedBeforeReplacement;

        try (var insertBlocker = dataSource.getConnection()) {
            insertBlocker.setAutoCommit(false);
            configureConnection(insertBlocker, "eligibility-profile-insert-blocker");
            lockRecipientTableInShareMode(insertBlocker);

            matchingFirstPublication = submit(
                    matchingFirstExecutor,
                    "eligibility-match-before-profile",
                    () -> publicationService.publish(publishCommand(
                            matchingFirst,
                            matchingFirst.finalizationId(),
                            1,
                            "match-before-profile-replacement")));
            awaitLockWait("eligibility-match-before-profile");

            replaceTargetProfile(matchingFirst);
            assertProvider(matchingFirst, "VERIFIED", 3, 2);

            insertBlocker.rollback();
            observedBeforeReplacement = get("matching before profile replacement", matchingFirstPublication);
        } finally {
            cancel(matchingFirstPublication);
            shutdown(matchingFirstExecutor);
        }

        var oldProfileRequestId = observedBeforeReplacement.request().requestId();
        assertThat(recipientProviders(oldProfileRequestId))
                .containsExactlyInAnyOrder(matchingFirst.targetProviderId(), matchingFirst.stableProviderId());
        assertRecipient(oldProfileRequestId, matchingFirst.targetProviderId(), 2);
        assertThat(detailService.find(
                        matchingFirst.targetProviderId(), oldProfileRequestId, matchingFirst.staffId()).requestId())
                .isEqualTo(oldProfileRequestId);
        assertFeedContainsExactly(matchingFirst, oldProfileRequestId);
        assertReplacementProfile(matchingFirst);

        var profileFirst = fixture(true);
        var profileFirstExecutor = workerExecutor();
        Future<RequestPublicationResponse> profileFirstPublication = null;
        RequestPublicationResponse observedAfterReplacement;

        try (var groupLockOwner = dataSource.getConnection()) {
            groupLockOwner.setAutoCommit(false);
            configureConnection(groupLockOwner, "eligibility-group-lock-owner");
            lockGroupForUpdate(groupLockOwner, profileFirst.groupId());

            profileFirstPublication = submit(
                    profileFirstExecutor,
                    "eligibility-profile-before-match",
                    () -> publicationService.publish(publishCommand(
                            profileFirst,
                            profileFirst.finalizationId(),
                            1,
                            "profile-replacement-before-match")));
            awaitLockWait("eligibility-profile-before-match");

            replaceTargetProfile(profileFirst);
            groupLockOwner.rollback();
            observedAfterReplacement = get("profile replacement before matching", profileFirstPublication);
        } finally {
            cancel(profileFirstPublication);
            shutdown(profileFirstExecutor);
        }

        var newProfileRequestId = observedAfterReplacement.request().requestId();
        assertThat(recipientProviders(newProfileRequestId)).containsExactly(profileFirst.stableProviderId());
        assertThatThrownBy(() -> detailService.find(
                        profileFirst.targetProviderId(), newProfileRequestId, profileFirst.staffId()))
                .isInstanceOf(ProviderPublishedRequestDetailException.class);
        assertThat(feedService.list(
                        profileFirst.targetProviderId(), profileFirst.staffId(), null, 100).items())
                .isEmpty();
        assertProvider(profileFirst, "VERIFIED", 3, 2);
        assertReplacementProfile(profileFirst);
    }

    private Fixture fixture(boolean includeStableProvider) {
        var organizerId = UUID.randomUUID();
        var staffId = UUID.randomUUID();
        var operatorId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var windowId = UUID.randomUUID();
        var targetProviderId = UUID.randomUUID();
        var stableProviderId = includeStableProvider ? UUID.randomUUID() : null;
        var areaCode = "AREA-" + planId.toString().substring(0, 8);
        var correlationId = "eligibility-race-" + planId;
        var startAt = jdbcClient.sql("SELECT clock_timestamp() + INTERVAL '3 days'")
                .query(OffsetDateTime.class)
                .single();

        insertAccounts(organizerId, staffId, operatorId);
        jdbcClient.sql("""
                        INSERT INTO group_account (
                            group_id, name, description, status, created_by_account_id
                        ) VALUES (:groupId, 'Eligibility race group', '', 'ACTIVE', :organizerId)
                        """)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at)
                        VALUES (:groupId, :organizerId, 'ORGANIZER', 'ACTIVE', statement_timestamp())
                        """)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (
                            plan_id, group_id, title, state, created_by_account_id, version
                        ) VALUES (
                            :planId, :groupId, 'Eligibility race plan', 'COLLABORATING', :organizerId, 1
                        )
                        """)
                .param("planId", planId)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km,
                            minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units,
                            provider_safe_notes, category_attributes
                        ) VALUES (
                            :planId, 'COURT', 'Asia/Manila', :areaCode, 5,
                            4, 10, 'PHP', 100000, 250000,
                            'Eligibility race notes.', '{"courtCount":2}'::jsonb
                        )
                        """)
                .param("planId", planId)
                .param("areaCode", areaCode)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (
                            candidate_window_id, plan_id, sort_order, starts_at, ends_at
                        ) VALUES (:windowId, :planId, 1, :startAt, :endAt)
                        """)
                .param("windowId", windowId)
                .param("planId", planId)
                .param("startAt", startAt)
                .param("endAt", startAt.plusHours(2))
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_must_have (plan_id, must_have, sort_order)
                        VALUES (:planId, 'parking', 1)
                        """)
                .param("planId", planId)
                .update();

        insertVerifiedProvider(targetProviderId, "Target race provider", areaCode);
        jdbcClient.sql("""
                        INSERT INTO provider_staff_membership (provider_id, account_id, role, status)
                        VALUES (:providerId, :staffId, 'ADMIN', 'ACTIVE')
                        """)
                .param("providerId", targetProviderId)
                .param("staffId", staffId)
                .update();
        if (stableProviderId != null) {
            insertVerifiedProvider(stableProviderId, "Stable race provider", areaCode);
        }

        var fixture = new Fixture(
                organizerId,
                staffId,
                operatorId,
                groupId,
                planId,
                windowId,
                null,
                targetProviderId,
                stableProviderId,
                areaCode,
                startAt,
                correlationId);
        return fixture.withFinalizationId(insertFinalization(fixture, 1));
    }

    private void insertAccounts(UUID... accountIds) {
        for (var accountId : accountIds) {
            jdbcClient.sql("""
                            INSERT INTO identity_account (account_id, display_name)
                            VALUES (:accountId, 'Eligibility race account')
                            """)
                    .param("accountId", accountId)
                    .update();
        }
    }

    private void insertVerifiedProvider(UUID providerId, String displayName, String areaCode) {
        jdbcClient.sql("""
                        INSERT INTO provider_organization (
                            provider_id, display_name, status, verification_status,
                            version, eligibility_version
                        ) VALUES (:providerId, :displayName, 'ACTIVE', 'VERIFIED', 2, 2)
                        """)
                .param("providerId", providerId)
                .param("displayName", displayName)
                .update();
        jdbcClient.sql("""
                        INSERT INTO provider_supported_category (provider_id, category)
                        VALUES (:providerId, 'COURT')
                        """)
                .param("providerId", providerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO provider_service_area (provider_id, area_code)
                        VALUES (:providerId, :areaCode)
                        """)
                .param("providerId", providerId)
                .param("areaCode", areaCode)
                .update();
    }

    private UUID insertFinalization(Fixture fixture, long basisPlanVersion) {
        var finalizationId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_finalization (
                            finalization_id, plan_id, basis_plan_version,
                            selected_candidate_window_id, selected_starts_at, selected_ends_at,
                            offer_deadline, category, time_zone, area_code, radius_km,
                            minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                            provider_safe_notes, category_attributes, current_preference_count,
                            stale_preference_count, warnings, finalized_by_account_id, created_at
                        ) VALUES (
                            :finalizationId, :planId, :basisPlanVersion,
                            :windowId, :startAt, :endAt,
                            :offerDeadline, 'COURT', 'Asia/Manila', :areaCode, 5,
                            4, 10, 'PHP', 100000, 250000, ARRAY['parking']::varchar[],
                            'Eligibility race notes.', '{"courtCount":2}'::jsonb, 0, 0,
                            ARRAY['NO_CURRENT_PREFERENCE_INPUT']::varchar[],
                            :organizerId, statement_timestamp()
                        )
                        """)
                .param("finalizationId", finalizationId)
                .param("planId", fixture.planId())
                .param("basisPlanVersion", basisPlanVersion)
                .param("windowId", fixture.windowId())
                .param("startAt", fixture.startAt())
                .param("endAt", fixture.startAt().plusHours(2))
                .param("offerDeadline", fixture.startAt().minusHours(1))
                .param("areaCode", fixture.areaCode())
                .param("organizerId", fixture.organizerId())
                .update();
        return finalizationId;
    }

    private PublishRequestCommand publishCommand(
            Fixture fixture, UUID finalizationId, long expectedPlanVersion, String key) {
        return new PublishRequestCommand(
                fixture.organizerId(),
                fixture.planId(),
                finalizationId,
                expectedPlanVersion,
                key,
                fixture.correlationId());
    }

    private SuspendProviderCommand suspensionCommand(Fixture fixture, String key) {
        return new SuspendProviderCommand(
                fixture.operatorId(),
                Set.of(PlatformRole.PLATFORM_OPERATOR),
                fixture.targetProviderId(),
                key,
                "Eligibility race suspension.",
                fixture.correlationId());
    }

    private RestoreProviderCommand restorationCommand(Fixture fixture, String key) {
        return new RestoreProviderCommand(
                fixture.operatorId(),
                Set.of(PlatformRole.PLATFORM_OPERATOR),
                fixture.targetProviderId(),
                key,
                fixture.correlationId());
    }

    private void replaceTargetProfile(Fixture fixture) {
        profileService.replace(new ReplaceProviderProfileCommand(
                fixture.staffId(),
                fixture.targetProviderId(),
                2,
                "Replaced race provider",
                List.of(ProviderCategory.KTV),
                List.of("OTHER-" + fixture.areaCode()),
                fixture.correlationId()));
    }

    private void assertSuspendedPrivacy(Fixture fixture, UUID requestId) {
        assertThatThrownBy(() -> detailService.find(fixture.targetProviderId(), requestId, fixture.staffId()))
                .isInstanceOf(ProviderPublishedRequestDetailException.class);
        assertThatThrownBy(() -> feedService.list(
                        fixture.targetProviderId(), fixture.staffId(), null, 100))
                .isInstanceOfSatisfying(ProviderRequestFeedException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ProviderRequestFeedException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
    }

    private void assertRestoredStalePrivacy(Fixture fixture, UUID requestId) {
        assertThatThrownBy(() -> detailService.find(fixture.targetProviderId(), requestId, fixture.staffId()))
                .isInstanceOf(ProviderPublishedRequestDetailException.class);
        assertThat(feedService.list(fixture.targetProviderId(), fixture.staffId(), null, 100).items())
                .isEmpty();
    }

    private void assertFeedContainsExactly(Fixture fixture, UUID... requestIds) {
        assertThat(feedService.list(fixture.targetProviderId(), fixture.staffId(), null, 100).items())
                .extracting(item -> item.requestId())
                .containsExactly(requestIds);
    }

    private void assertProvider(Fixture fixture, String status, long version, long eligibilityVersion) {
        var actual = jdbcClient.sql("""
                        SELECT verification_status, version, eligibility_version
                        FROM provider_organization
                        WHERE provider_id = :providerId
                        """)
                .param("providerId", fixture.targetProviderId())
                .query((resultSet, rowNumber) -> new ProviderState(
                        resultSet.getString("verification_status"),
                        resultSet.getLong("version"),
                        resultSet.getLong("eligibility_version")))
                .single();
        assertThat(actual).isEqualTo(new ProviderState(status, version, eligibilityVersion));
    }

    private void assertRecipient(UUID requestId, UUID providerId, long eligibilityVersion) {
        var actual = jdbcClient.sql("""
                        SELECT provider_eligibility_version
                        FROM marketplace_request_recipient
                        WHERE published_request_id = :requestId
                          AND provider_id = :providerId
                        """)
                .param("requestId", requestId)
                .param("providerId", providerId)
                .query(Long.class)
                .single();
        assertThat(actual).isEqualTo(eligibilityVersion);
    }

    private List<UUID> recipientProviders(UUID requestId) {
        return jdbcClient.sql("""
                        SELECT provider_id
                        FROM marketplace_request_recipient
                        WHERE published_request_id = :requestId
                        ORDER BY provider_id
                        """)
                .param("requestId", requestId)
                .query(UUID.class)
                .list();
    }

    private long recipientCount(UUID providerId, long eligibilityVersion) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM marketplace_request_recipient
                        WHERE provider_id = :providerId
                          AND provider_eligibility_version = :eligibilityVersion
                        """)
                .param("providerId", providerId)
                .param("eligibilityVersion", eligibilityVersion)
                .query(Long.class)
                .single();
    }

    private void assertReplacementProfile(Fixture fixture) {
        assertThat(jdbcClient.sql("""
                        SELECT category
                        FROM provider_supported_category
                        WHERE provider_id = :providerId
                        """)
                .param("providerId", fixture.targetProviderId())
                .query(String.class)
                .list()).containsExactly("KTV");
        assertThat(jdbcClient.sql("""
                        SELECT area_code
                        FROM provider_service_area
                        WHERE provider_id = :providerId
                        """)
                .param("providerId", fixture.targetProviderId())
                .query(String.class)
                .list()).containsExactly("OTHER-" + fixture.areaCode());
    }

    private ExecutorService workerExecutor() {
        return Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("eligibility-race-worker").factory());
    }

    private <T> Future<T> submit(ExecutorService executor, String applicationName, Supplier<T> work) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            configureCurrentTransaction(applicationName);
            return work.get();
        }));
    }

    private void configureCurrentTransaction(String applicationName) {
        var connection = DataSourceUtils.getConnection(dataSource);
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '8s'");
            statement.execute("SET LOCAL statement_timeout = '8s'");
            statement.execute("SET LOCAL application_name = '" + applicationName + "'");
        } catch (SQLException exception) {
            throw new IllegalStateException("could not configure race worker transaction", exception);
        }
    }

    private void configureConnection(Connection connection, String applicationName) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '8s'");
            statement.execute("SET LOCAL statement_timeout = '8s'");
            statement.execute("SET LOCAL application_name = '" + applicationName + "'");
        }
    }

    private void lockRecipientTableInShareMode(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("LOCK TABLE marketplace_request_recipient IN SHARE MODE");
        }
    }

    private void lockProviderForNoKeyUpdate(Connection connection, UUID providerId) throws SQLException {
        lockRow(
                connection,
                "SELECT provider_id FROM provider_organization WHERE provider_id = ? FOR NO KEY UPDATE",
                providerId);
    }

    private void lockGroupForUpdate(Connection connection, UUID groupId) throws SQLException {
        lockRow(
                connection,
                "SELECT group_id FROM group_account WHERE group_id = ? FOR UPDATE",
                groupId);
    }

    private void lockRow(Connection connection, String sql, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("race lock target does not exist");
                }
            }
        }
    }

    private void awaitLockWait(String applicationName) throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS));
        while (Instant.now().isBefore(deadline)) {
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement("""
                            SELECT EXISTS (
                                SELECT 1
                                FROM pg_stat_activity
                                WHERE application_name = ?
                                  AND wait_event_type = 'Lock'
                            )
                            """)) {
                statement.setString(1, applicationName);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new IllegalStateException(
                "worker did not reach the expected PostgreSQL lock wait: " + lockDiagnostics());
    }

    private <T> T get(String operation, Future<T> result) throws Exception {
        try {
            return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(operation + " did not finish before timeout: " + lockDiagnostics(), exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof Exception workerException) {
                throw workerException;
            }
            throw exception;
        }
    }

    private String lockDiagnostics() {
        return jdbcClient.sql("""
                        SELECT coalesce(string_agg(
                            application_name || ':' || state || ':'
                            || coalesce(wait_event_type, 'none') || ':' || coalesce(wait_event, 'none'),
                            ', ' ORDER BY application_name), 'no eligibility race workers')
                        FROM pg_stat_activity
                        WHERE application_name LIKE 'eligibility-%'
                        """)
                .query(String.class)
                .single();
    }

    private void cancel(Future<?> result) {
        if (result != null && !result.isDone()) {
            result.cancel(true);
        }
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("eligibility race worker did not stop before timeout");
        }
    }

    private record Fixture(
            UUID organizerId,
            UUID staffId,
            UUID operatorId,
            UUID groupId,
            UUID planId,
            UUID windowId,
            UUID finalizationId,
            UUID targetProviderId,
            UUID stableProviderId,
            String areaCode,
            OffsetDateTime startAt,
            String correlationId) {

        private Fixture withFinalizationId(UUID value) {
            return new Fixture(
                    organizerId,
                    staffId,
                    operatorId,
                    groupId,
                    planId,
                    windowId,
                    value,
                    targetProviderId,
                    stableProviderId,
                    areaCode,
                    startAt,
                    correlationId);
        }
    }

    private record ProviderState(String status, long version, long eligibilityVersion) {
    }
}
