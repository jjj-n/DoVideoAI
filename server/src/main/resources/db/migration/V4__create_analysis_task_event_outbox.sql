CREATE TABLE IF NOT EXISTS analysis_task_event_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    event_payload LONGTEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claim_token VARCHAR(64) NULL,
    claimed_until TIMESTAMP(3) NULL,
    published_at TIMESTAMP(3) NULL,
    last_error VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_analysis_event_outbox_pending (published_at, next_attempt_at, claimed_until, id),
    KEY idx_analysis_event_outbox_media (media_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
