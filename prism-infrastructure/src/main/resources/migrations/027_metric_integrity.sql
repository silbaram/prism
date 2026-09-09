-- MySQL 8+. One-time migration for an existing installation; see docs/issue-27.md.
-- Stop API/admin writes and back up the database before running. DDL commits implicitly.
-- Leave existing goals unset: each experiment owner must select its real primary event.
ALTER TABLE experiments
    ADD COLUMN goal_event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;

-- Preserve all previously unattributed events before enforcing the exposure policy.
CREATE TABLE log_conversion_unattributed_archive LIKE log_conversion;
INSERT INTO log_conversion_unattributed_archive SELECT * FROM log_conversion WHERE variant IS NULL;
DELETE FROM log_conversion WHERE variant IS NULL;
ALTER TABLE log_conversion
    MODIFY variant VARCHAR(255) NOT NULL,
    MODIFY event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD INDEX idx_conversion_goal (experiment_key, event_name, variant);
