CREATE TABLE planning_published_request (
    request_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES planning_plan (plan_id),
    request_version BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL,
    distribution_mode VARCHAR(20) NOT NULL,
    category VARCHAR(20) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    area_code VARCHAR(64) NOT NULL,
    radius_km INTEGER NOT NULL,
    requested_starts_at TIMESTAMPTZ NOT NULL,
    requested_ends_at TIMESTAMPTZ NOT NULL,
    minimum_headcount INTEGER NOT NULL,
    maximum_headcount INTEGER NOT NULL,
    budget_currency CHAR(3),
    budget_minimum_minor_units BIGINT,
    budget_maximum_minor_units BIGINT,
    must_haves VARCHAR[] NOT NULL,
    provider_safe_notes VARCHAR(1000),
    category_attributes JSONB NOT NULL,
    offer_deadline TIMESTAMPTZ NOT NULL,
    published_by_account_id UUID NOT NULL REFERENCES identity_account (account_id),
    published_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    closed_at TIMESTAMPTZ,
    CONSTRAINT planning_published_request_plan_request_key UNIQUE (plan_id, request_id),
    CONSTRAINT planning_published_request_plan_version_key UNIQUE (plan_id, request_version),
    CONSTRAINT planning_published_request_version_check CHECK (request_version > 0),
    CONSTRAINT planning_published_request_state_check CHECK (
        state IN ('OPEN', 'SUPERSEDED', 'CLOSED', 'CANCELLED')
    ),
    CONSTRAINT planning_published_request_distribution_check CHECK (
        distribution_mode = 'MATCHED_POOL'
    ),
    CONSTRAINT planning_published_request_schedule_check CHECK (
        published_at < offer_deadline
        AND offer_deadline < requested_starts_at
        AND requested_starts_at < requested_ends_at
    ),
    CONSTRAINT planning_published_request_category_check CHECK (
        category IN ('COURT', 'KTV', 'GROUP_DINING')
    ),
    CONSTRAINT planning_published_request_time_zone_check CHECK (
        char_length(time_zone) BETWEEN 1 AND 64 AND btrim(time_zone) = time_zone
    ),
    CONSTRAINT planning_published_request_area_code_check CHECK (
        char_length(area_code) BETWEEN 1 AND 64 AND btrim(area_code) <> ''
    ),
    CONSTRAINT planning_published_request_radius_check CHECK (radius_km BETWEEN 1 AND 100),
    CONSTRAINT planning_published_request_headcount_check CHECK (
        minimum_headcount BETWEEN 1 AND 100
        AND maximum_headcount BETWEEN 1 AND 100
        AND minimum_headcount <= maximum_headcount
    ),
    CONSTRAINT planning_published_request_budget_check CHECK (
        (budget_currency IS NULL AND budget_minimum_minor_units IS NULL AND budget_maximum_minor_units IS NULL)
        OR (
            budget_currency IS NOT NULL
            AND budget_currency = 'PHP'
            AND budget_minimum_minor_units IS NOT NULL
            AND budget_maximum_minor_units IS NOT NULL
            AND budget_minimum_minor_units BETWEEN 0 AND 100000000
            AND budget_maximum_minor_units BETWEEN 0 AND 100000000
            AND budget_minimum_minor_units <= budget_maximum_minor_units
        )
    ),
    CONSTRAINT planning_published_request_must_haves_check CHECK (
        planning_finalization_must_haves_valid(must_haves)
    ),
    CONSTRAINT planning_published_request_notes_check CHECK (
        provider_safe_notes IS NULL OR char_length(provider_safe_notes) <= 1000
    ),
    CONSTRAINT planning_published_request_attributes_check CHECK (
        planning_category_attributes_valid(category_attributes)
    ),
    CONSTRAINT planning_published_request_lifecycle_check CHECK (
        (state = 'OPEN' AND closed_at IS NULL)
        OR (state <> 'OPEN' AND closed_at IS NOT NULL AND published_at <= closed_at)
    )
);

CREATE UNIQUE INDEX planning_published_request_one_open_key
    ON planning_published_request (plan_id)
    WHERE state = 'OPEN';

CREATE INDEX planning_published_request_plan_history_idx
    ON planning_published_request (plan_id, request_version DESC);

ALTER TABLE planning_plan
    ADD COLUMN current_request_id UUID;

ALTER TABLE planning_plan
    DROP CONSTRAINT planning_plan_state_check;

ALTER TABLE planning_plan
    ADD CONSTRAINT planning_plan_state_check CHECK (
        state IN ('COLLABORATING', 'OPEN_FOR_OFFERS', 'CANCELLED')
    ),
    ADD CONSTRAINT planning_plan_current_request_state_check CHECK (
        (state = 'OPEN_FOR_OFFERS') = (current_request_id IS NOT NULL)
    ),
    ADD CONSTRAINT planning_plan_current_request_fk
        FOREIGN KEY (plan_id, current_request_id)
        REFERENCES planning_published_request (plan_id, request_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION prevent_planning_published_request_snapshot_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        NEW.request_id,
        NEW.plan_id,
        NEW.request_version,
        NEW.distribution_mode,
        NEW.category,
        NEW.time_zone,
        NEW.area_code,
        NEW.radius_km,
        NEW.requested_starts_at,
        NEW.requested_ends_at,
        NEW.minimum_headcount,
        NEW.maximum_headcount,
        NEW.budget_currency,
        NEW.budget_minimum_minor_units,
        NEW.budget_maximum_minor_units,
        NEW.must_haves,
        NEW.provider_safe_notes,
        NEW.category_attributes,
        NEW.offer_deadline,
        NEW.published_by_account_id,
        NEW.published_at
    ) IS DISTINCT FROM ROW(
        OLD.request_id,
        OLD.plan_id,
        OLD.request_version,
        OLD.distribution_mode,
        OLD.category,
        OLD.time_zone,
        OLD.area_code,
        OLD.radius_km,
        OLD.requested_starts_at,
        OLD.requested_ends_at,
        OLD.minimum_headcount,
        OLD.maximum_headcount,
        OLD.budget_currency,
        OLD.budget_minimum_minor_units,
        OLD.budget_maximum_minor_units,
        OLD.must_haves,
        OLD.provider_safe_notes,
        OLD.category_attributes,
        OLD.offer_deadline,
        OLD.published_by_account_id,
        OLD.published_at
    ) THEN
        RAISE EXCEPTION 'published request snapshots are immutable';
    END IF;

    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NEW;
    END IF;

    IF OLD.state = 'OPEN'
        AND NEW.state IN ('SUPERSEDED', 'CLOSED', 'CANCELLED')
        AND OLD.closed_at IS NULL
        AND NEW.closed_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'published request lifecycle changes must be terminal';
END;
$$;

CREATE TRIGGER protect_planning_published_request_snapshot
BEFORE UPDATE ON planning_published_request
FOR EACH ROW
EXECUTE FUNCTION prevent_planning_published_request_snapshot_mutation();

CREATE FUNCTION prevent_planning_published_request_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'published requests cannot be deleted';
END;
$$;

CREATE TRIGGER protect_planning_published_request_history
BEFORE DELETE ON planning_published_request
FOR EACH ROW
EXECUTE FUNCTION prevent_planning_published_request_delete();

CREATE FUNCTION check_planning_current_request_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    checked_plan_id UUID;
    checked_plan_state VARCHAR(20);
    checked_current_request_id UUID;
    checked_open_request_id UUID;
BEGIN
    checked_plan_id := NEW.plan_id;

    SELECT p.state, p.current_request_id
    INTO checked_plan_state, checked_current_request_id
    FROM planning_plan p
    WHERE p.plan_id = checked_plan_id;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT r.request_id
    INTO checked_open_request_id
    FROM planning_published_request r
    WHERE r.plan_id = checked_plan_id
      AND r.state = 'OPEN';

    IF checked_plan_state = 'OPEN_FOR_OFFERS'
        AND checked_current_request_id IS NOT NULL
        AND checked_current_request_id = checked_open_request_id THEN
        RETURN NULL;
    END IF;

    IF checked_plan_state <> 'OPEN_FOR_OFFERS'
        AND checked_current_request_id IS NULL
        AND checked_open_request_id IS NULL THEN
        RETURN NULL;
    END IF;

    RAISE EXCEPTION 'plan current request must identify its one open same-plan request';
END;
$$;

CREATE CONSTRAINT TRIGGER check_planning_plan_current_request
AFTER INSERT OR UPDATE ON planning_plan
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_planning_current_request_consistency();

CREATE CONSTRAINT TRIGGER check_planning_request_current_plan
AFTER INSERT OR UPDATE ON planning_published_request
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_planning_current_request_consistency();
