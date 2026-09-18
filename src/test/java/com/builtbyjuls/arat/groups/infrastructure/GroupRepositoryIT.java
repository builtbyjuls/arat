package com.builtbyjuls.arat.groups.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.domain.Group;
import com.builtbyjuls.arat.groups.domain.GroupStatus;
import com.builtbyjuls.arat.groups.domain.Membership;
import com.builtbyjuls.arat.groups.domain.MembershipRole;
import com.builtbyjuls.arat.groups.domain.MembershipStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.groups-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GroupRepositoryIT extends PostgreSqlIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID GROUP_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Autowired
    private GroupRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:ownerId, :memberId)")
                .param("ownerId", OWNER_ID)
                .param("memberId", MEMBER_ID)
                .update();
        insertAccount(OWNER_ID, "Group owner");
        insertAccount(MEMBER_ID, "Group member");
    }

    @Test
    void persistsGroupsAndDistinguishesPrivateAndMembershipLookups() {
        var createdAt = OffsetDateTime.parse("2026-09-18T06:00:00Z");
        var group = group(createdAt);
        var activeMembership = membership(MEMBER_ID, MembershipStatus.ACTIVE, createdAt, null);
        var inactiveMembership = membership(OWNER_ID, MembershipStatus.LEFT, createdAt, createdAt.plusHours(1));

        inTransaction(() -> {
            repository.insert(group);
            repository.insertMembership(activeMembership);
            repository.insertMembership(inactiveMembership);
            return null;
        });

        assertThat(repository.findById(GROUP_ID)).contains(group);
        assertThat(repository.findPrivate(GROUP_ID, MEMBER_ID)).contains(group);
        assertThat(repository.findPrivate(GROUP_ID, OWNER_ID)).isEmpty();
        assertThat(repository.findMembership(GROUP_ID, OWNER_ID)).contains(inactiveMembership);
        assertThat(repository.findActiveMembership(GROUP_ID, OWNER_ID)).isEmpty();
        assertThat(repository.findActiveMembership(GROUP_ID, MEMBER_ID)).contains(activeMembership);
    }

    @Test
    void rejectsDuplicateGroupMembershipAtTheDatabaseBoundary() {
        var now = OffsetDateTime.parse("2026-09-18T06:00:00Z");
        inTransaction(() -> {
            repository.insert(group(now));
            repository.insertMembership(membership(MEMBER_ID, MembershipStatus.ACTIVE, now, null));
            return null;
        });

        assertThatThrownBy(() -> inTransaction(() -> {
            repository.insertMembership(membership(MEMBER_ID, MembershipStatus.ACTIVE, now, null));
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void locksTheGroupRootAndAdvancesVersionOnlyThroughVersionedUpdate() {
        var now = OffsetDateTime.parse("2026-09-18T06:00:00Z");
        inTransaction(() -> {
            repository.insert(group(now));
            assertThat(repository.lockGroup(GROUP_ID).version()).isOne();
            return null;
        });

        var updated = inTransaction(() -> repository.updateVersioned(GROUP_ID, 1, "Updated group", "New description"));
        assertThat(updated).isPresent();
        assertThat(updated.orElseThrow().version()).isEqualTo(2);
        assertThat(repository.findById(GROUP_ID).orElseThrow().version()).isEqualTo(2);
        assertThat(inTransaction(() -> repository.updateVersioned(GROUP_ID, 1, "Stale", "Stale"))).isEmpty();
        assertThat(repository.findById(GROUP_ID).orElseThrow().name()).isEqualTo("Updated group");
    }

    @Test
    void holdsTheGroupLockAcrossTransactionsUntilTheOwnerCommits() throws Exception {
        var now = OffsetDateTime.parse("2026-09-18T06:00:00Z");
        inTransaction(() -> {
            repository.insert(group(now));
            return null;
        });

        var firstLockAcquired = new CountDownLatch(1);
        var releaseFirstLock = new CountDownLatch(1);
        var secondLockStarted = new CountDownLatch(1);
        var secondLockCompleted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactionTemplate.execute(status -> {
                repository.lockGroup(GROUP_ID);
                firstLockAcquired.countDown();
                await(releaseFirstLock);
                return null;
            }));

            assertThat(firstLockAcquired.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> transactionTemplate.execute(status -> {
                secondLockStarted.countDown();
                repository.lockGroup(GROUP_ID);
                secondLockCompleted.countDown();
                return null;
            }));

            assertThat(secondLockStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondLockCompleted.await(250, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirstLock.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void databaseEnforcesFrozenGroupNameAndDescriptionBounds() {
        var now = OffsetDateTime.parse("2026-09-18T06:00:00Z");
        assertThatThrownBy(() -> inTransaction(() -> {
            jdbcClient.sql("""
                            INSERT INTO group_account (
                                group_id, name, description, created_by_account_id, version, created_at, updated_at
                            )
                            VALUES (:groupId, :name, '', :ownerId, 1, :now, :now)
                            """)
                    .param("groupId", GROUP_ID)
                    .param("name", "x".repeat(81))
                    .param("ownerId", OWNER_ID)
                    .param("now", now)
                    .update();
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO group_account (
                            group_id, name, description, created_by_account_id, version, created_at, updated_at
                        )
                        VALUES (:groupId, 'Valid name', :description, :ownerId, 1, :now, :now)
                        """)
                .param("groupId", GROUP_ID)
                .param("description", "x".repeat(501))
                .param("ownerId", OWNER_ID)
                .param("now", now)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Group group(OffsetDateTime timestamp) {
        return new Group(GROUP_ID, "Friday outing", "Private plan", GroupStatus.ACTIVE, OWNER_ID, 1, timestamp, timestamp);
    }

    private Membership membership(UUID accountId, MembershipStatus status, OffsetDateTime joinedAt, OffsetDateTime endedAt) {
        return new Membership(GROUP_ID, accountId, MembershipRole.MEMBER, status, joinedAt, endedAt);
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

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test transaction was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test transaction was interrupted", exception);
        }
    }
}
