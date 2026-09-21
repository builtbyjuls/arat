CREATE TABLE planning_plan_preference (
    plan_id UUID NOT NULL REFERENCES planning_plan (plan_id),
    account_id UUID NOT NULL REFERENCES identity_account (account_id),
    basis_plan_version BIGINT NOT NULL,
    attendance VARCHAR(20) NOT NULL,
    guest_count INTEGER NOT NULL,
    personal_budget_currency CHAR(3),
    personal_budget_minor_units BIGINT,
    private_note VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT planning_plan_preference_pkey PRIMARY KEY (plan_id, account_id),
    CONSTRAINT planning_plan_preference_basis_version_positive CHECK (basis_plan_version > 0),
    CONSTRAINT planning_plan_preference_attendance_check CHECK (
        attendance IN ('INTERESTED', 'AVAILABLE', 'JOINING', 'NOT_JOINING')
    ),
    CONSTRAINT planning_plan_preference_guest_count_bounds CHECK (
        guest_count BETWEEN 0 AND 20
        AND (attendance <> 'NOT_JOINING' OR guest_count = 0)
    ),
    CONSTRAINT planning_plan_preference_personal_budget_check CHECK (
        (personal_budget_currency IS NULL AND personal_budget_minor_units IS NULL)
        OR (
            personal_budget_currency IS NOT NULL
            AND personal_budget_currency = 'PHP'
            AND personal_budget_minor_units IS NOT NULL
            AND personal_budget_minor_units BETWEEN 0 AND 100000000
        )
    ),
    CONSTRAINT planning_plan_preference_version_positive CHECK (version > 0)
);

CREATE INDEX planning_plan_preference_plan_projection_idx
    ON planning_plan_preference (plan_id, account_id);

CREATE UNIQUE INDEX planning_candidate_window_plan_id_key
    ON planning_candidate_window (plan_id, candidate_window_id);

CREATE TABLE planning_preference_selected_window (
    plan_id UUID NOT NULL,
    account_id UUID NOT NULL,
    candidate_window_id UUID NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT planning_preference_selected_window_pkey PRIMARY KEY (plan_id, account_id, candidate_window_id),
    CONSTRAINT planning_preference_selected_window_preference_fk FOREIGN KEY (plan_id, account_id)
        REFERENCES planning_plan_preference (plan_id, account_id) ON DELETE CASCADE,
    CONSTRAINT planning_preference_selected_window_candidate_window_fk FOREIGN KEY (plan_id, candidate_window_id)
        REFERENCES planning_candidate_window (plan_id, candidate_window_id),
    CONSTRAINT planning_preference_selected_window_sort_order_positive CHECK (sort_order > 0),
    CONSTRAINT planning_preference_selected_window_order_key UNIQUE (plan_id, account_id, sort_order)
);

CREATE INDEX planning_preference_selected_window_plan_projection_idx
    ON planning_preference_selected_window (plan_id, candidate_window_id);

CREATE TABLE planning_preference_ranked_item (
    plan_id UUID NOT NULL,
    account_id UUID NOT NULL,
    preference_text VARCHAR(80) NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT planning_preference_ranked_item_pkey PRIMARY KEY (plan_id, account_id, preference_text),
    CONSTRAINT planning_preference_ranked_item_preference_fk FOREIGN KEY (plan_id, account_id)
        REFERENCES planning_plan_preference (plan_id, account_id) ON DELETE CASCADE,
    CONSTRAINT planning_preference_ranked_item_value_bounds CHECK (
        char_length(preference_text) BETWEEN 1 AND 80 AND btrim(preference_text) = preference_text
    ),
    CONSTRAINT planning_preference_ranked_item_sort_order_bounds CHECK (sort_order BETWEEN 1 AND 10),
    CONSTRAINT planning_preference_ranked_item_order_key UNIQUE (plan_id, account_id, sort_order)
);
