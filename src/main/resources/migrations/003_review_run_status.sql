-- 已有数据库执行一次；新数据库直接使用 schema.sql。
-- 若已有其他状态（包括 SUPERSEDED），需先按实际任务结果处理，再执行约束变更。
-- 本迁移不自动改写任务状态。
ALTER TABLE review_run
    MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT 'PENDING/RUNNING/PUBLICATION_READY/PUBLISHED/FAILED',
    ADD CONSTRAINT chk_review_run_status CHECK (status IN ('PENDING', 'RUNNING', 'PUBLICATION_READY', 'PUBLISHED', 'FAILED'));
