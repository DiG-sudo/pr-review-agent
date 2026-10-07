-- Existing databases: run once. Fresh databases use schema.sql.
ALTER TABLE review_run
    ADD COLUMN model_calls INT NOT NULL DEFAULT 0,
    ADD COLUMN max_model_calls INT NULL;

-- Legacy initialized tasks have no historical counter; the old count cannot be recovered.
-- Set 20 to the desired budget for those tasks before running this migration.
UPDATE review_run
SET max_model_calls = 20
WHERE initial_messages_json IS NOT NULL;
