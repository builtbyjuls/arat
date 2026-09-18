CREATE TABLE audit_event (
    event_id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_id UUID NOT NULL REFERENCES identity_account (account_id),
    action VARCHAR(120) NOT NULL,
    subject_type VARCHAR(120) NOT NULL,
    subject_id UUID NOT NULL,
    group_id UUID,
    plan_id UUID,
    correlation_id VARCHAR(120) NOT NULL,
    metadata JSONB NOT NULL,
    CONSTRAINT audit_event_action_not_blank CHECK (btrim(action) <> ''),
    CONSTRAINT audit_event_subject_type_not_blank CHECK (btrim(subject_type) <> ''),
    CONSTRAINT audit_event_correlation_id_not_blank CHECK (btrim(correlation_id) <> ''),
    CONSTRAINT audit_event_metadata_is_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT audit_event_metadata_size_check CHECK (octet_length(metadata::TEXT) <= 2048)
);

CREATE INDEX audit_event_subject_idx ON audit_event (subject_type, subject_id);
CREATE INDEX audit_event_group_idx ON audit_event (group_id) WHERE group_id IS NOT NULL;
CREATE INDEX audit_event_plan_idx ON audit_event (plan_id) WHERE plan_id IS NOT NULL;
