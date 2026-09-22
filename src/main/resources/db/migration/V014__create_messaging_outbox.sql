CREATE TABLE messaging_outbox (
    event_id UUID PRIMARY KEY,
    business_key VARCHAR(256) NOT NULL UNIQUE,
    event_type VARCHAR(120) NOT NULL,
    schema_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    aggregate_type VARCHAR(120) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    trace_id VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    available_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT messaging_outbox_business_key_not_blank CHECK (btrim(business_key, E' \t\r\n') <> ''),
    CONSTRAINT messaging_outbox_event_type_not_blank CHECK (btrim(event_type, E' \t\r\n') <> ''),
    CONSTRAINT messaging_outbox_schema_version_positive CHECK (schema_version > 0),
    CONSTRAINT messaging_outbox_aggregate_type_not_blank CHECK (btrim(aggregate_type, E' \t\r\n') <> ''),
    CONSTRAINT messaging_outbox_aggregate_version_positive CHECK (aggregate_version > 0),
    CONSTRAINT messaging_outbox_trace_id_not_blank CHECK (btrim(trace_id, E' \t\r\n') <> ''),
    CONSTRAINT messaging_outbox_payload_is_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT messaging_outbox_payload_size_check CHECK (octet_length(payload::TEXT) <= 4096),
    CONSTRAINT messaging_outbox_delivery_status_pending CHECK (delivery_status = 'PENDING')
);

CREATE INDEX messaging_outbox_pending_delivery_idx
    ON messaging_outbox (available_at, created_at, event_id)
    WHERE published_at IS NULL;

CREATE FUNCTION messaging_outbox_reject_immutable_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.event_id IS DISTINCT FROM OLD.event_id
        OR NEW.business_key IS DISTINCT FROM OLD.business_key
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
        OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.aggregate_version IS DISTINCT FROM OLD.aggregate_version
        OR NEW.trace_id IS DISTINCT FROM OLD.trace_id
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'messaging outbox event content is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER messaging_outbox_reject_immutable_update
BEFORE UPDATE ON messaging_outbox
FOR EACH ROW
EXECUTE FUNCTION messaging_outbox_reject_immutable_update();
