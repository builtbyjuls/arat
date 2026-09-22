CREATE TABLE provider_organization (
    provider_id UUID PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    version BIGINT NOT NULL DEFAULT 1,
    eligibility_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT provider_organization_display_name_bounds CHECK (
        char_length(display_name) BETWEEN 1 AND 120
        AND display_name !~ '^[[:space:]]*$'
    ),
    CONSTRAINT provider_organization_status_check CHECK (status IN ('ACTIVE')),
    CONSTRAINT provider_organization_verification_status_check CHECK (
        verification_status IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'REJECTED', 'SUSPENDED')
    ),
    CONSTRAINT provider_organization_version_positive CHECK (version > 0),
    CONSTRAINT provider_organization_eligibility_version_positive CHECK (eligibility_version > 0)
);

CREATE TABLE provider_staff_membership (
    provider_id UUID NOT NULL REFERENCES provider_organization (provider_id),
    account_id UUID NOT NULL REFERENCES identity_account (account_id),
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    removed_at TIMESTAMPTZ,
    CONSTRAINT provider_staff_membership_pkey PRIMARY KEY (provider_id, account_id),
    CONSTRAINT provider_staff_membership_role_check CHECK (role IN ('ADMIN', 'STAFF')),
    CONSTRAINT provider_staff_membership_status_check CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT provider_staff_membership_removed_at_check CHECK (
        (status = 'ACTIVE' AND removed_at IS NULL)
        OR (status = 'REMOVED' AND removed_at IS NOT NULL)
    )
);

CREATE INDEX provider_staff_membership_active_authorization_idx
    ON provider_staff_membership (provider_id, account_id)
    WHERE status = 'ACTIVE';

CREATE TABLE provider_supported_category (
    provider_id UUID NOT NULL REFERENCES provider_organization (provider_id),
    category VARCHAR(20) NOT NULL,
    CONSTRAINT provider_supported_category_pkey PRIMARY KEY (provider_id, category),
    CONSTRAINT provider_supported_category_value_check CHECK (
        category IN ('COURT', 'KTV', 'GROUP_DINING')
    )
);

CREATE TABLE provider_service_area (
    provider_id UUID NOT NULL REFERENCES provider_organization (provider_id),
    area_code VARCHAR(64) NOT NULL,
    CONSTRAINT provider_service_area_pkey PRIMARY KEY (provider_id, area_code),
    CONSTRAINT provider_service_area_code_bounds CHECK (
        char_length(area_code) BETWEEN 1 AND 64
        AND area_code = btrim(area_code, E' \t\n\r\f\v')
        AND area_code !~ '^[[:space:]]*$'
    )
);

CREATE FUNCTION provider_service_area_limit()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.provider_id = OLD.provider_id THEN
        RETURN NEW;
    END IF;

    PERFORM 1
    FROM provider_organization
    WHERE provider_id = NEW.provider_id
    FOR NO KEY UPDATE;

    IF (
        SELECT count(*)
        FROM provider_service_area
        WHERE provider_id = NEW.provider_id
    ) >= 20 THEN
        RAISE EXCEPTION 'provider service area limit exceeded';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER provider_service_area_limit_trigger
BEFORE INSERT OR UPDATE OF provider_id ON provider_service_area
FOR EACH ROW
EXECUTE FUNCTION provider_service_area_limit();
