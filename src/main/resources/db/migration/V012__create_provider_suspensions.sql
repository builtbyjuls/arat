CREATE TABLE provider_suspension (
    suspension_id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES provider_organization (provider_id),
    suspended_by_account_id UUID NOT NULL REFERENCES identity_account (account_id),
    suspension_reason VARCHAR(500) NOT NULL,
    suspended_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT provider_suspension_reason_bounds CHECK (
        char_length(suspension_reason) BETWEEN 1 AND 500
        AND suspension_reason = btrim(suspension_reason, E' \t\n\r\f\v')
    )
);

CREATE INDEX provider_suspension_provider_suspended_idx
    ON provider_suspension (provider_id, suspended_at DESC, suspension_id DESC);

CREATE FUNCTION prevent_provider_suspension_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'provider suspensions are immutable';
END;
$$;

CREATE TRIGGER prevent_provider_suspension_update_or_delete
BEFORE UPDATE OR DELETE ON provider_suspension
FOR EACH ROW
EXECUTE FUNCTION prevent_provider_suspension_mutation();
