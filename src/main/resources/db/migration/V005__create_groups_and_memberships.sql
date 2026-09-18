CREATE TABLE group_account (
    group_id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_account_id UUID NOT NULL REFERENCES identity_account (account_id),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT group_account_name_bounds CHECK (char_length(name) BETWEEN 1 AND 80 AND btrim(name) <> ''),
    CONSTRAINT group_account_description_bounds CHECK (char_length(description) <= 500),
    CONSTRAINT group_account_status_check CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT group_account_version_positive CHECK (version > 0)
);

CREATE TABLE group_membership (
    group_id UUID NOT NULL REFERENCES group_account (group_id),
    account_id UUID NOT NULL REFERENCES identity_account (account_id),
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    ended_at TIMESTAMPTZ,
    CONSTRAINT group_membership_pkey PRIMARY KEY (group_id, account_id),
    CONSTRAINT group_membership_role_check CHECK (role IN ('MEMBER', 'ORGANIZER')),
    CONSTRAINT group_membership_status_check CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    CONSTRAINT group_membership_ended_at_check CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL)
        OR (status IN ('LEFT', 'REMOVED') AND ended_at IS NOT NULL)
    )
);

CREATE INDEX group_membership_active_authorization_idx
    ON group_membership (group_id, account_id)
    WHERE status = 'ACTIVE';

CREATE INDEX group_membership_listing_idx
    ON group_membership (group_id, status, account_id);
