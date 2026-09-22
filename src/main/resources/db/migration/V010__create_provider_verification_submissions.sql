CREATE TABLE provider_verification_submission (
    submission_id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES provider_organization (provider_id),
    submitted_by_account_id UUID NOT NULL REFERENCES identity_account (account_id),
    evidence_count INTEGER NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT provider_verification_submission_evidence_count_bounds CHECK (evidence_count BETWEEN 1 AND 10)
);

CREATE INDEX provider_verification_submission_provider_submitted_idx
    ON provider_verification_submission (provider_id, submitted_at DESC, submission_id DESC);

CREATE TABLE provider_verification_evidence_reference (
    submission_id UUID NOT NULL REFERENCES provider_verification_submission (submission_id),
    sort_order INTEGER NOT NULL,
    evidence_reference VARCHAR(256) NOT NULL,
    CONSTRAINT provider_verification_evidence_reference_pkey PRIMARY KEY (submission_id, sort_order),
    CONSTRAINT provider_verification_evidence_reference_unique_key UNIQUE (submission_id, evidence_reference),
    CONSTRAINT provider_verification_evidence_reference_order_bounds CHECK (sort_order BETWEEN 1 AND 10),
    CONSTRAINT provider_verification_evidence_reference_value_bounds CHECK (
        char_length(evidence_reference) BETWEEN 1 AND 256
        AND octet_length(evidence_reference) = char_length(evidence_reference)
        AND evidence_reference !~ '[^ -~]'
    )
);

CREATE FUNCTION provider_verification_submission_evidence_count_valid(p_submission_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM provider_verification_submission
        WHERE submission_id = p_submission_id
          AND evidence_count = (
              SELECT count(*)
              FROM provider_verification_evidence_reference
              WHERE submission_id = p_submission_id
          )
          AND evidence_count * (evidence_count + 1) / 2 = (
              SELECT COALESCE(sum(sort_order), 0)
              FROM provider_verification_evidence_reference
              WHERE submission_id = p_submission_id
          )
    );
$$;

CREATE FUNCTION provider_verification_submission_parent_evidence_count_trigger()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT provider_verification_submission_evidence_count_valid(NEW.submission_id) THEN
        RAISE EXCEPTION 'provider verification submission evidence count does not match';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION provider_verification_submission_child_evidence_count_trigger()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_submission_id UUID;
BEGIN
    affected_submission_id := COALESCE(NEW.submission_id, OLD.submission_id);
    IF NOT provider_verification_submission_evidence_count_valid(affected_submission_id) THEN
        RAISE EXCEPTION 'provider verification submission evidence count does not match';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER provider_verification_submission_evidence_count_trigger
AFTER INSERT ON provider_verification_submission
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION provider_verification_submission_parent_evidence_count_trigger();

CREATE CONSTRAINT TRIGGER provider_verification_evidence_reference_count_trigger
AFTER INSERT OR UPDATE OR DELETE ON provider_verification_evidence_reference
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION provider_verification_submission_child_evidence_count_trigger();

CREATE FUNCTION prevent_provider_verification_submission_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'provider verification submissions are immutable';
END;
$$;

CREATE TRIGGER prevent_provider_verification_submission_update_or_delete
BEFORE UPDATE OR DELETE ON provider_verification_submission
FOR EACH ROW
EXECUTE FUNCTION prevent_provider_verification_submission_mutation();

CREATE TRIGGER prevent_provider_verification_evidence_mutation
BEFORE UPDATE OR DELETE ON provider_verification_evidence_reference
FOR EACH ROW
EXECUTE FUNCTION prevent_provider_verification_submission_mutation();
