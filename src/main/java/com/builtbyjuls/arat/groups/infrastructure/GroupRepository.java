package com.builtbyjuls.arat.groups.infrastructure;

import com.builtbyjuls.arat.groups.domain.Group;
import com.builtbyjuls.arat.groups.domain.GroupStatus;
import com.builtbyjuls.arat.groups.domain.Membership;
import com.builtbyjuls.arat.groups.domain.MembershipRole;
import com.builtbyjuls.arat.groups.domain.MembershipStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class GroupRepository {

    private final JdbcClient jdbcClient;

    public GroupRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Group group) {
        jdbcClient.sql("""
                        INSERT INTO group_account (
                            group_id, name, description, status, created_by_account_id,
                            version, created_at, updated_at
                        )
                        VALUES (
                            :groupId, :name, :description, :status, :createdByAccountId,
                            :version, :createdAt, :updatedAt
                        )
                        """)
                .param("groupId", group.groupId())
                .param("name", group.name())
                .param("description", group.description())
                .param("status", group.status().name())
                .param("createdByAccountId", group.createdByAccountId())
                .param("version", group.version())
                .param("createdAt", group.createdAt())
                .param("updatedAt", group.updatedAt())
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Group insertNew(UUID groupId, String name, String description, UUID createdByAccountId) {
        return jdbcClient.sql("""
                        INSERT INTO group_account (
                            group_id, name, description, status, created_by_account_id, version, created_at, updated_at
                        )
                        VALUES (
                            :groupId, :name, :description, 'ACTIVE', :createdByAccountId, 1,
                            statement_timestamp(), statement_timestamp()
                        )
                        RETURNING group_id, name, description, status, created_by_account_id,
                                  version, created_at, updated_at
                        """)
                .param("groupId", groupId)
                .param("name", name)
                .param("description", description)
                .param("createdByAccountId", createdByAccountId)
                .query(this::mapGroup)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertOrganizerMembership(UUID groupId, UUID accountId) {
        jdbcClient.sql("""
                        INSERT INTO group_membership (
                            group_id, account_id, role, status, joined_at, ended_at
                        )
                        VALUES (
                            :groupId, :accountId, 'ORGANIZER', 'ACTIVE', statement_timestamp(), NULL
                        )
                        """)
                .param("groupId", groupId)
                .param("accountId", accountId)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertMembership(Membership membership) {
        jdbcClient.sql("""
                        INSERT INTO group_membership (
                            group_id, account_id, role, status, joined_at, ended_at
                        )
                        VALUES (
                            :groupId, :accountId, :role, :status, :joinedAt, :endedAt
                        )
                        """)
                .param("groupId", membership.groupId())
                .param("accountId", membership.accountId())
                .param("role", membership.role().name())
                .param("status", membership.status().name())
                .param("joinedAt", membership.joinedAt())
                .param("endedAt", membership.endedAt())
                .update();
    }

    @Transactional(readOnly = true)
    public Optional<Group> findById(UUID groupId) {
        return jdbcClient.sql("""
                        SELECT group_id, name, description, status, created_by_account_id,
                               version, created_at, updated_at
                        FROM group_account
                        WHERE group_id = :groupId
                        """)
                .param("groupId", groupId)
                .query(this::mapGroup)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<Group> findPrivate(UUID groupId, UUID accountId) {
        return jdbcClient.sql("""
                        SELECT g.group_id, g.name, g.description, g.status, g.created_by_account_id,
                               g.version, g.created_at, g.updated_at
                        FROM group_account g
                        JOIN group_membership m ON m.group_id = g.group_id
                        WHERE g.group_id = :groupId
                          AND m.account_id = :accountId
                          AND m.status = 'ACTIVE'
                        """)
                .param("groupId", groupId)
                .param("accountId", accountId)
                .query(this::mapGroup)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<Membership> findMembership(UUID groupId, UUID accountId) {
        return findMembership(groupId, accountId, "");
    }

    @Transactional(readOnly = true)
    public Optional<Membership> findActiveMembership(UUID groupId, UUID accountId) {
        return findMembership(groupId, accountId, "AND status = 'ACTIVE'");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Group lockGroup(UUID groupId) {
        return jdbcClient.sql("""
                        SELECT group_id, name, description, status, created_by_account_id,
                               version, created_at, updated_at
                        FROM group_account
                        WHERE group_id = :groupId
                        FOR UPDATE
                        """)
                .param("groupId", groupId)
                .query(this::mapGroup)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Group> updateVersioned(UUID groupId, long expectedVersion, String name, String description) {
        var updated = jdbcClient.sql("""
                        UPDATE group_account
                        SET name = :name,
                            description = :description,
                            version = version + 1,
                            updated_at = statement_timestamp()
                        WHERE group_id = :groupId
                          AND version = :expectedVersion
                        RETURNING group_id, name, description, status, created_by_account_id,
                                  version, created_at, updated_at
                        """)
                .param("groupId", groupId)
                .param("expectedVersion", expectedVersion)
                .param("name", name)
                .param("description", description)
                .query(this::mapGroup)
                .optional();
        return updated;
    }

    private Optional<Membership> findMembership(UUID groupId, UUID accountId, String statusPredicate) {
        return jdbcClient.sql("""
                        SELECT group_id, account_id, role, status, joined_at, ended_at
                        FROM group_membership
                        WHERE group_id = :groupId
                          AND account_id = :accountId
                        """ + statusPredicate)
                .param("groupId", groupId)
                .param("accountId", accountId)
                .query(this::mapMembership)
                .optional();
    }

    private Group mapGroup(ResultSet resultSet, int rowNum) throws SQLException {
        return new Group(
                resultSet.getObject("group_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("description"),
                GroupStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("created_by_account_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    private Membership mapMembership(ResultSet resultSet, int rowNum) throws SQLException {
        return new Membership(
                resultSet.getObject("group_id", UUID.class),
                resultSet.getObject("account_id", UUID.class),
                MembershipRole.valueOf(resultSet.getString("role")),
                MembershipStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("joined_at", OffsetDateTime.class),
                resultSet.getObject("ended_at", OffsetDateTime.class));
    }
}
