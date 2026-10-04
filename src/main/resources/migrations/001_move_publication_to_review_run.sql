-- Run once against an existing database. Fresh databases use schema.sql.
ALTER TABLE review_run
    ADD COLUMN publication_key VARCHAR(128) NULL,
    ADD COLUMN external_review_id VARCHAR(128) NULL;

-- Preserve values if any older tool rounds already recorded a publication.
UPDATE review_run AS run
SET publication_key = (
        SELECT round_row.publication_key
        FROM tool_round AS round_row
        WHERE round_row.run_id = run.id AND round_row.publication_key IS NOT NULL
        ORDER BY round_row.round_number DESC
        LIMIT 1
    ),
    external_review_id = (
        SELECT round_row.external_review_id
        FROM tool_round AS round_row
        WHERE round_row.run_id = run.id AND round_row.external_review_id IS NOT NULL
        ORDER BY round_row.round_number DESC
        LIMIT 1
    );

ALTER TABLE tool_round
    DROP COLUMN publication_key,
    DROP COLUMN external_review_id;
