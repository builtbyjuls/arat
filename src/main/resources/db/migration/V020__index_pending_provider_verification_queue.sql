CREATE INDEX provider_verification_submission_queue_idx
    ON provider_verification_submission (submitted_at DESC, submission_id DESC)
    INCLUDE (provider_id);
