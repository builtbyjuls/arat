CREATE FUNCTION planning_finalization_must_haves_valid(must_haves VARCHAR[])
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(
        cardinality(must_haves) <= 20
        AND cardinality(must_haves) = (
            SELECT count(DISTINCT must_have)
            FROM unnest(must_haves) AS must_have
        )
        AND NOT EXISTS (
            SELECT 1
            FROM unnest(must_haves) AS must_have
            WHERE must_have IS NULL
               OR char_length(must_have) NOT BETWEEN 1 AND 120
               OR btrim(must_have) = ''
        ),
        FALSE
    );
$$;

CREATE FUNCTION planning_finalization_warnings_valid(
    warnings VARCHAR[],
    current_preference_count INTEGER,
    stale_preference_count INTEGER
)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(
        cardinality(warnings) <= 2
        AND warnings <@ ARRAY[
            'NO_CURRENT_PREFERENCE_INPUT',
            'STALE_PREFERENCE_INPUT_PRESENT'
        ]::VARCHAR[]
        AND cardinality(warnings) = (
            SELECT count(DISTINCT warning)
            FROM unnest(warnings) AS warning
        )
        AND (current_preference_count = 0) = ('NO_CURRENT_PREFERENCE_INPUT' = ANY(warnings))
        AND (stale_preference_count > 0) = ('STALE_PREFERENCE_INPUT_PRESENT' = ANY(warnings)),
        FALSE
    );
$$;

CREATE TABLE planning_requirement_finalization (
    finalization_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES planning_plan (plan_id),
    basis_plan_version BIGINT NOT NULL,
    selected_candidate_window_id UUID NOT NULL,
    selected_starts_at TIMESTAMPTZ NOT NULL,
    selected_ends_at TIMESTAMPTZ NOT NULL,
    offer_deadline TIMESTAMPTZ NOT NULL,
    category VARCHAR(20) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    area_code VARCHAR(64) NOT NULL,
    radius_km INTEGER NOT NULL,
    minimum_headcount INTEGER NOT NULL,
    maximum_headcount INTEGER NOT NULL,
    budget_currency CHAR(3),
    budget_minimum_minor_units BIGINT,
    budget_maximum_minor_units BIGINT,
    must_haves VARCHAR[] NOT NULL,
    provider_safe_notes VARCHAR(1000),
    category_attributes JSONB NOT NULL,
    current_preference_count INTEGER NOT NULL,
    stale_preference_count INTEGER NOT NULL,
    warnings VARCHAR[] NOT NULL,
    finalized_by_account_id UUID NOT NULL REFERENCES identity_account (account_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT planning_requirement_finalization_window_fk FOREIGN KEY (plan_id, selected_candidate_window_id)
        REFERENCES planning_candidate_window (plan_id, candidate_window_id),
    CONSTRAINT planning_requirement_finalization_basis_version_check CHECK (basis_plan_version > 0),
    CONSTRAINT planning_requirement_finalization_schedule_check CHECK (
        selected_starts_at < selected_ends_at
        AND offer_deadline < selected_starts_at
    ),
    CONSTRAINT planning_requirement_finalization_category_check CHECK (category IN ('COURT', 'KTV', 'GROUP_DINING')),
    CONSTRAINT planning_requirement_finalization_time_zone_check CHECK (
        char_length(time_zone) BETWEEN 1 AND 64 AND btrim(time_zone) = time_zone
    ),
    CONSTRAINT planning_requirement_finalization_area_code_check CHECK (
        char_length(area_code) BETWEEN 1 AND 64 AND btrim(area_code) <> ''
    ),
    CONSTRAINT planning_requirement_finalization_radius_check CHECK (radius_km BETWEEN 1 AND 100),
    CONSTRAINT planning_requirement_finalization_headcount_check CHECK (
        minimum_headcount BETWEEN 1 AND 100
        AND maximum_headcount BETWEEN 1 AND 100
        AND minimum_headcount <= maximum_headcount
    ),
    CONSTRAINT planning_requirement_finalization_budget_check CHECK (
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
    CONSTRAINT planning_requirement_finalization_must_haves_check CHECK (
        planning_finalization_must_haves_valid(must_haves)
    ),
    CONSTRAINT planning_requirement_finalization_notes_check CHECK (
        provider_safe_notes IS NULL OR char_length(provider_safe_notes) <= 1000
    ),
    CONSTRAINT planning_requirement_finalization_attributes_check CHECK (
        planning_category_attributes_valid(category_attributes)
    ),
    CONSTRAINT planning_requirement_finalization_preference_counts_check CHECK (
        current_preference_count >= 0 AND stale_preference_count >= 0
    ),
    CONSTRAINT planning_requirement_finalization_warnings_check CHECK (
        planning_finalization_warnings_valid(
            warnings,
            current_preference_count,
            stale_preference_count
        )
    )
);

CREATE INDEX planning_requirement_finalization_plan_created_idx
    ON planning_requirement_finalization (plan_id, created_at DESC, finalization_id DESC);

CREATE FUNCTION prevent_planning_requirement_finalization_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'requirement finalizations are immutable';
END;
$$;

CREATE TRIGGER prevent_planning_requirement_finalization_update_or_delete
BEFORE UPDATE OR DELETE ON planning_requirement_finalization
FOR EACH ROW
EXECUTE FUNCTION prevent_planning_requirement_finalization_mutation();
