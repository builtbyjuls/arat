CREATE TABLE marketplace_request_recipient (
    published_request_id UUID NOT NULL REFERENCES planning_published_request (request_id),
    provider_id UUID NOT NULL REFERENCES provider_organization (provider_id),
    provider_eligibility_version BIGINT NOT NULL,
    source VARCHAR(32) NOT NULL,
    source_listing_id UUID,
    access_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT marketplace_request_recipient_pkey PRIMARY KEY (published_request_id, provider_id),
    CONSTRAINT marketplace_request_recipient_eligibility_version_positive CHECK (
        provider_eligibility_version > 0
    ),
    CONSTRAINT marketplace_request_recipient_source_check CHECK (
        source = 'MATCH_RULE'
    ),
    CONSTRAINT marketplace_request_recipient_source_listing_consistency CHECK (
        source_listing_id IS NULL
    ),
    CONSTRAINT marketplace_request_recipient_access_state_check CHECK (
        access_state IN ('ACTIVE', 'REVOKED')
    )
);

CREATE INDEX marketplace_request_recipient_provider_feed_idx
    ON marketplace_request_recipient (provider_id, created_at DESC, published_request_id DESC)
    WHERE access_state = 'ACTIVE';

CREATE INDEX marketplace_request_recipient_request_lookup_idx
    ON marketplace_request_recipient (published_request_id, created_at DESC, provider_id);

CREATE FUNCTION prevent_marketplace_request_recipient_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'request recipient grants are append-only'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER marketplace_request_recipient_reject_mutation
BEFORE UPDATE OR DELETE ON marketplace_request_recipient
FOR EACH ROW
EXECUTE FUNCTION prevent_marketplace_request_recipient_mutation();
