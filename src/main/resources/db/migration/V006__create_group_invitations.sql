CREATE TABLE group_invitation (
    invite_id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES group_account (group_id),
    invitee_account_id UUID NOT NULL REFERENCES identity_account (account_id),
    token_key_id VARCHAR(80) NOT NULL,
    nonce BYTEA NOT NULL,
    token_digest CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by_account_id UUID NOT NULL REFERENCES identity_account (account_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT group_invitation_nonce_length_check CHECK (octet_length(nonce) = 32),
    CONSTRAINT group_invitation_token_digest_check CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT group_invitation_state_check CHECK (state IN ('PENDING', 'EXPIRED', 'REVOKED', 'CONSUMED'))
);

CREATE UNIQUE INDEX group_invitation_token_digest_key ON group_invitation (token_digest);

CREATE UNIQUE INDEX group_invitation_one_pending_per_invitee
    ON group_invitation (group_id, invitee_account_id)
    WHERE state = 'PENDING';

CREATE INDEX group_invitation_organizer_lookup_idx
    ON group_invitation (group_id, created_by_account_id, state, invite_id);

CREATE INDEX group_invitation_acceptance_lookup_idx
    ON group_invitation (token_digest, state, expires_at);
