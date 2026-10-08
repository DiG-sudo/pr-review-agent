CREATE TABLE IF NOT EXISTS review_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    thread_id VARCHAR(255) NOT NULL,
    head_sha CHAR(40) NOT NULL,
    base_sha CHAR(40) NULL,
    repository VARCHAR(255) NOT NULL,
    pull_request_number INT NOT NULL,
    status VARCHAR(32) NOT NULL COMMENT 'PENDING/RUNNING/PUBLICATION_READY/PUBLISHED/FAILED',
    review_state_json JSON NULL,
    final_result_json JSON NULL,
    publication_payload_json JSON NULL,
    publication_key VARCHAR(128) NULL,
    external_review_id VARCHAR(128) NULL,
    UNIQUE KEY uq_review_run_thread_revision (thread_id, head_sha),
    CONSTRAINT chk_review_run_status CHECK (status IN ('PENDING', 'RUNNING', 'PUBLICATION_READY', 'PUBLISHED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS review_agent (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    agent_index INT NOT NULL,
    success TINYINT(1) NULL DEFAULT NULL,
    file_paths_json JSON NULL,
    initial_messages_json JSON NOT NULL,
    review_state_json JSON NOT NULL,
    model_calls INT NOT NULL DEFAULT 0,
    max_model_calls INT NOT NULL,
    UNIQUE KEY uq_review_agent_index (run_id, agent_index),
    KEY ix_review_agent_run_result (run_id, success),
    CONSTRAINT fk_review_agent_run FOREIGN KEY (run_id) REFERENCES review_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE IF NOT EXISTS tool_round (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    round_number INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    assistant_message_json JSON NOT NULL,
    tool_response_json JSON NULL,
    UNIQUE KEY uq_tool_round_agent_number (agent_id, round_number),
    KEY ix_tool_round_agent_status (agent_id, status),
    CONSTRAINT fk_tool_round_agent FOREIGN KEY (agent_id) REFERENCES review_agent (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
