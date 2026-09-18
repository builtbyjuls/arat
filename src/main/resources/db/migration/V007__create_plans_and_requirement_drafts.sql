CREATE FUNCTION planning_category_attributes_valid(attributes JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    attribute_key TEXT;
    attribute_value JSONB;
    attribute_count INTEGER := 0;
    numeric_value NUMERIC;
BEGIN
    IF jsonb_typeof(attributes) <> 'object' THEN
        RETURN FALSE;
    END IF;

    FOR attribute_key, attribute_value IN SELECT key, value FROM jsonb_each(attributes)
    LOOP
        attribute_count := attribute_count + 1;
        IF attribute_count > 20
            OR attribute_key !~ '^[a-z][A-Za-z0-9]{0,39}$' THEN
            RETURN FALSE;
        END IF;

        CASE jsonb_typeof(attribute_value)
            WHEN 'boolean' THEN NULL;
            WHEN 'string' THEN
                IF char_length(attribute_value #>> '{}') NOT BETWEEN 1 AND 120 THEN
                    RETURN FALSE;
                END IF;
            WHEN 'number' THEN
                IF (attribute_value #>> '{}') !~ '^(0|[1-9][0-9]*)$' THEN
                    RETURN FALSE;
                END IF;
                numeric_value := (attribute_value #>> '{}')::NUMERIC;
                IF numeric_value > 1000000 THEN
                    RETURN FALSE;
                END IF;
            ELSE
                RETURN FALSE;
        END CASE;
    END LOOP;

    RETURN TRUE;
END;
$$;

CREATE TABLE planning_plan (
    plan_id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES group_account (group_id),
    title VARCHAR(120) NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_by_account_id UUID NOT NULL REFERENCES identity_account (account_id),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT planning_plan_title_bounds CHECK (char_length(title) BETWEEN 1 AND 120 AND btrim(title) <> ''),
    CONSTRAINT planning_plan_state_check CHECK (state IN ('COLLABORATING', 'CANCELLED')),
    CONSTRAINT planning_plan_version_positive CHECK (version > 0)
);

CREATE INDEX planning_plan_group_created_listing_idx
    ON planning_plan (group_id, created_at DESC, plan_id DESC);

CREATE TABLE planning_requirement_draft (
    plan_id UUID PRIMARY KEY REFERENCES planning_plan (plan_id),
    category VARCHAR(20) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    area_code VARCHAR(64) NOT NULL,
    radius_km INTEGER NOT NULL,
    minimum_headcount INTEGER NOT NULL,
    maximum_headcount INTEGER NOT NULL,
    budget_currency CHAR(3),
    budget_minimum_minor_units BIGINT,
    budget_maximum_minor_units BIGINT,
    provider_safe_notes VARCHAR(1000),
    category_attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT planning_requirement_draft_category_check CHECK (category IN ('COURT', 'KTV', 'GROUP_DINING')),
    CONSTRAINT planning_requirement_draft_time_zone_bounds CHECK (char_length(time_zone) BETWEEN 1 AND 64 AND btrim(time_zone) = time_zone),
    CONSTRAINT planning_requirement_draft_area_code_bounds CHECK (char_length(area_code) BETWEEN 1 AND 64 AND btrim(area_code) <> ''),
    CONSTRAINT planning_requirement_draft_radius_bounds CHECK (radius_km BETWEEN 1 AND 100),
    CONSTRAINT planning_requirement_draft_headcount_bounds CHECK (
        minimum_headcount BETWEEN 1 AND 100
        AND maximum_headcount BETWEEN 1 AND 100
        AND minimum_headcount <= maximum_headcount
    ),
    CONSTRAINT planning_requirement_draft_budget_check CHECK (
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
    CONSTRAINT planning_requirement_draft_provider_safe_notes_bounds CHECK (
        provider_safe_notes IS NULL OR char_length(provider_safe_notes) <= 1000
    ),
    CONSTRAINT planning_requirement_draft_category_attributes_check CHECK (
        planning_category_attributes_valid(category_attributes)
    )
);

CREATE TABLE planning_candidate_window (
    candidate_window_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES planning_plan (plan_id),
    sort_order INTEGER NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    retired_at TIMESTAMPTZ,
    CONSTRAINT planning_candidate_window_sort_order_positive CHECK (sort_order > 0),
    CONSTRAINT planning_candidate_window_interval_check CHECK (starts_at < ends_at)
);

CREATE UNIQUE INDEX planning_candidate_window_active_order_key
    ON planning_candidate_window (plan_id, sort_order)
    WHERE retired_at IS NULL;

CREATE TABLE planning_requirement_must_have (
    plan_id UUID NOT NULL REFERENCES planning_plan (plan_id),
    must_have VARCHAR(120) NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT planning_requirement_must_have_pkey PRIMARY KEY (plan_id, must_have),
    CONSTRAINT planning_requirement_must_have_sort_order_positive CHECK (sort_order > 0),
    CONSTRAINT planning_requirement_must_have_value_bounds CHECK (
        char_length(must_have) BETWEEN 1 AND 120 AND btrim(must_have) <> ''
    ),
    CONSTRAINT planning_requirement_must_have_order_key UNIQUE (plan_id, sort_order)
);
