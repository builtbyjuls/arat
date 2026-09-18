package com.builtbyjuls.arat.groups.infrastructure;

import com.builtbyjuls.arat.groups.domain.Invitation;
import com.builtbyjuls.arat.groups.domain.InvitationState;
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
public class InvitationRepository {

    private final JdbcClient jdbcClient;

    public InvitationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int expirePending(UUID groupId, UUID inviteeAccountId) {
        return jdbcClient.sql("""
                        UPDATE group_invitation
                        SET state = 'EXPIRED', updated_at = statement_timestamp()
                        WHERE group_id = :groupId
                          AND invitee_account_id = :inviteeAccountId
                          AND state = 'PENDING'
                          AND expires_at <= statement_timestamp()
                        """)
                .param("groupId", groupId)
                .param("inviteeAccountId", inviteeAccountId)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Invitation insert(UUID inviteId, UUID groupId, UUID inviteeAccountId, String tokenKeyId,
            byte[] nonce, String tokenDigest, int expiryHours, UUID createdByAccountId) {
        return jdbcClient.sql("""
                        INSERT INTO group_invitation (
                            invite_id, group_id, invitee_account_id, token_key_id, nonce,
                            token_digest, state, expires_at, created_by_account_id, created_at, updated_at
                        )
                        VALUES (
                            :inviteId, :groupId, :inviteeAccountId, :tokenKeyId, :nonce,
                            :tokenDigest, 'PENDING', statement_timestamp() + make_interval(hours => :expiryHours),
                            :createdByAccountId, statement_timestamp(), statement_timestamp()
                        )
                        RETURNING invite_id, group_id, invitee_account_id, token_key_id, nonce, token_digest,
                                  state, expires_at, created_by_account_id, created_at, updated_at
                        """)
                .param("inviteId", inviteId)
                .param("groupId", groupId)
                .param("inviteeAccountId", inviteeAccountId)
                .param("tokenKeyId", tokenKeyId)
                .param("nonce", nonce)
                .param("tokenDigest", tokenDigest)
                .param("expiryHours", expiryHours)
                .param("createdByAccountId", createdByAccountId)
                .query(this::mapInvitation)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Invitation> revokePending(UUID groupId, UUID inviteId) {
        return jdbcClient.sql("""
                        UPDATE group_invitation
                        SET state = 'REVOKED', updated_at = statement_timestamp()
                        WHERE group_id = :groupId
                          AND invite_id = :inviteId
                          AND state = 'PENDING'
                          AND expires_at > statement_timestamp()
                        RETURNING invite_id, group_id, invitee_account_id, token_key_id, nonce, token_digest,
                                  state, expires_at, created_by_account_id, created_at, updated_at
                        """)
                .param("groupId", groupId)
                .param("inviteId", inviteId)
                .query(this::mapInvitation)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<Invitation> findById(UUID inviteId) {
        return jdbcClient.sql("""
                        SELECT invite_id, group_id, invitee_account_id, token_key_id, nonce, token_digest,
                               state, expires_at, created_by_account_id, created_at, updated_at
                        FROM group_invitation
                        WHERE invite_id = :inviteId
                        """)
                .param("inviteId", inviteId)
                .query(this::mapInvitation)
                .optional();
    }

    private Invitation mapInvitation(ResultSet resultSet, int rowNum) throws SQLException {
        return new Invitation(
                resultSet.getObject("invite_id", UUID.class),
                resultSet.getObject("group_id", UUID.class),
                resultSet.getObject("invitee_account_id", UUID.class),
                resultSet.getString("token_key_id"),
                resultSet.getBytes("nonce"),
                resultSet.getString("token_digest"),
                InvitationState.valueOf(resultSet.getString("state")),
                resultSet.getObject("expires_at", OffsetDateTime.class),
                resultSet.getObject("created_by_account_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }
}
