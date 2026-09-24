CREATE INDEX group_membership_active_actor_listing_idx
    ON group_membership (account_id, group_id)
    INCLUDE (role)
    WHERE status = 'ACTIVE';
