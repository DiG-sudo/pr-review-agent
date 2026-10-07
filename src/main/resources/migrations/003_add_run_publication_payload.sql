-- Existing databases: run once. Fresh databases use schema.sql.
ALTER TABLE review_run ADD COLUMN publication_payload_json JSON NULL;

-- Copy existing publication bodies while retaining the tool-round copy.
UPDATE review_run AS run
SET publication_payload_json = (
    SELECT round_row.publication_payload_json
    FROM tool_round AS round_row
    WHERE round_row.run_id = run.id
      AND round_row.status = 'COMPLETED'
      AND round_row.publication_payload_json IS NOT NULL
    ORDER BY round_row.round_number DESC
    LIMIT 1
)
WHERE run.publication_payload_json IS NULL;
