ALTER TABLE provider_verification_submission
    ADD CONSTRAINT provider_verification_submission_provider_identity_key
    UNIQUE (provider_id, submission_id);

ALTER TABLE provider_organization
    ADD COLUMN current_verification_submission_id UUID;

ALTER TABLE provider_organization
    ADD CONSTRAINT provider_organization_current_verification_submission_fkey
    FOREIGN KEY (provider_id, current_verification_submission_id)
    REFERENCES provider_verification_submission (provider_id, submission_id)
    DEFERRABLE INITIALLY DEFERRED;

UPDATE provider_organization AS organization
SET current_verification_submission_id = (
    SELECT submission_id
    FROM provider_verification_submission AS submission
    WHERE submission.provider_id = organization.provider_id
    ORDER BY submitted_at DESC, submission_id DESC
    LIMIT 1
)
WHERE verification_status = 'PENDING';

ALTER TABLE provider_organization
    ADD CONSTRAINT provider_organization_current_verification_submission_check CHECK (
        (verification_status = 'PENDING' AND current_verification_submission_id IS NOT NULL)
        OR (verification_status <> 'PENDING' AND current_verification_submission_id IS NULL)
    );

CREATE TABLE provider_verification_decision (
    decision_id UUID PRIMARY KEY,
    provider_id UUID NOT NULL,
    submission_id UUID NOT NULL UNIQUE,
    decided_by_account_id UUID NOT NULL REFERENCES identity_account (account_id),
    decision VARCHAR(10) NOT NULL,
    decision_note VARCHAR(500),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT provider_verification_decision_value_check CHECK (decision IN ('ACCEPT', 'REJECT')),
    CONSTRAINT provider_verification_decision_submission_fkey
        FOREIGN KEY (provider_id, submission_id)
        REFERENCES provider_verification_submission (provider_id, submission_id),
    CONSTRAINT provider_verification_decision_note_bounds CHECK (
        decision_note IS NULL
        OR (
            char_length(decision_note) BETWEEN 1 AND 500
            AND decision_note = btrim(decision_note, E' \t\n\r\f\v')
        )
    )
);

CREATE INDEX provider_verification_decision_provider_decided_idx
    ON provider_verification_decision (provider_id, decided_at DESC, decision_id DESC);

CREATE FUNCTION prevent_provider_verification_decision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'provider verification decisions are immutable';
END;
$$;

CREATE TRIGGER prevent_provider_verification_decision_update_or_delete
BEFORE UPDATE OR DELETE ON provider_verification_decision
FOR EACH ROW
EXECUTE FUNCTION prevent_provider_verification_decision_mutation();
