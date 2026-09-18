CREATE TABLE identity_account (
    account_id UUID PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    CONSTRAINT identity_account_display_name_not_blank CHECK (btrim(display_name) <> '')
);
