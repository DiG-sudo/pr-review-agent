-- Existing databases: run once while the application is stopped.
-- Fresh databases use schema.sql.
CREATE TABLE review_agent (
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

-- Existing initialized runs become one Agent so their histories remain recoverable.
INSERT INTO review_agent (
    run_id, agent_index, success, file_paths_json, initial_messages_json,
    review_state_json, model_calls, max_model_calls
)
SELECT id,
       0,
       CASE
           WHEN status IN ('PUBLICATION_READY', 'PUBLISHED') THEN TRUE
           WHEN status = 'FAILED' THEN FALSE
           ELSE NULL
       END,
       NULL,
       initial_messages_json,
       COALESCE(review_state_json, JSON_OBJECT('findings', JSON_ARRAY())),
       model_calls,
       COALESCE(max_model_calls, 20)
FROM review_run
WHERE initial_messages_json IS NOT NULL;

ALTER TABLE tool_round ADD COLUMN agent_id BIGINT NULL AFTER id;

UPDATE tool_round AS round_row
JOIN review_agent AS agent
  ON agent.run_id = round_row.run_id AND agent.agent_index = 0
SET round_row.agent_id = agent.id;

ALTER TABLE tool_round
    DROP FOREIGN KEY fk_tool_round_run,
    DROP INDEX uq_tool_round_run_number,
    DROP INDEX ix_tool_round_run_status,
    MODIFY COLUMN agent_id BIGINT NOT NULL,
    ADD UNIQUE KEY uq_tool_round_agent_number (agent_id, round_number),
    ADD KEY ix_tool_round_agent_status (agent_id, status),
    ADD CONSTRAINT fk_tool_round_agent FOREIGN KEY (agent_id) REFERENCES review_agent (id),
    DROP COLUMN run_id,
    DROP COLUMN publication_payload_json;

ALTER TABLE review_run
    DROP COLUMN initial_messages_json,
    DROP COLUMN model_calls,
    DROP COLUMN max_model_calls;
