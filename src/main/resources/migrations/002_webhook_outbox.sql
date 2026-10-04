-- Run once against an existing database. Fresh databases use schema.sql.
ALTER TABLE review_run
    ADD COLUMN base_sha CHAR(40) NULL,
    MODIFY COLUMN initial_messages_json JSON NULL,
    MODIFY COLUMN review_state_json JSON NULL;

CREATE TABLE IF NOT EXISTS outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at DATETIME(6) NULL,
    last_error VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_outbox_run_event (run_id, event_type),
    KEY ix_outbox_due (status, next_attempt_at, id),
    CONSTRAINT fk_outbox_run FOREIGN KEY (run_id) REFERENCES review_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
