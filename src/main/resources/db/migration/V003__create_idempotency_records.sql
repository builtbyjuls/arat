CREATE FUNCTION idempotency_response_headers_are_allowed(headers JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT jsonb_typeof(headers) = 'object'
       AND NOT EXISTS (
           SELECT 1
           FROM jsonb_object_keys(headers) AS header(name)
           WHERE name NOT IN ('ETag', 'Location')
              OR jsonb_typeof(headers -> name) <> 'string'
       );
$$;

CREATE TABLE idempotency_record (
    actor_id UUID NOT NULL REFERENCES identity_account (account_id),
    operation VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    resource_id UUID,
    response_status SMALLINT,
    replay_state JSONB,
    response_headers JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_record_pkey PRIMARY KEY (actor_id, operation, idempotency_key),
    CONSTRAINT idempotency_record_state_check
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_record_fingerprint_check
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_record_response_status_check
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT idempotency_record_replay_state_size_check
        CHECK (replay_state IS NULL OR octet_length(replay_state::TEXT) <= 16384),
    CONSTRAINT idempotency_record_response_headers_size_check
        CHECK (response_headers IS NULL OR octet_length(response_headers::TEXT) <= 2048),
    CONSTRAINT idempotency_record_response_headers_check
        CHECK (response_headers IS NULL OR idempotency_response_headers_are_allowed(response_headers)),
    CONSTRAINT idempotency_record_completion_check CHECK (
        (state = 'IN_PROGRESS'
            AND response_status IS NULL
            AND replay_state IS NULL
            AND response_headers IS NULL)
        OR (state = 'COMPLETED'
            AND response_status IS NOT NULL
            AND replay_state IS NOT NULL
            AND response_headers IS NOT NULL)
    ),
    CONSTRAINT idempotency_record_retention_check
        CHECK (expires_at = created_at + INTERVAL '7 days')
);

CREATE INDEX idempotency_record_expires_at_idx ON idempotency_record (expires_at);
