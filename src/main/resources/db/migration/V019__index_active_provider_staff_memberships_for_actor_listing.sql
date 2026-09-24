CREATE INDEX provider_staff_membership_active_actor_listing_idx
    ON provider_staff_membership (account_id, provider_id)
    INCLUDE (role)
    WHERE status = 'ACTIVE';
