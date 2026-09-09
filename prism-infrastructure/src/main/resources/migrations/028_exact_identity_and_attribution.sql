-- MySQL 8.0.17+. Run once AFTER 027_metric_integrity.sql on existing installations.
-- Stop API/admin writes and back up first. DDL commits implicitly.
-- Exact comparisons match SDK strings, including case, accents and trailing spaces.
ALTER TABLE experiments
    MODIFY experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY goal_event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL;

ALTER TABLE variants
    MODIFY name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

ALTER TABLE log_impression
    MODIFY experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY variant VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    DROP INDEX idx_user_experiment,
    ADD INDEX idx_user_experiment (user_id, experiment_key, id);

-- Historical rows retain NULL and the legacy timestamp eligibility check.
-- Do not guess an exposure ID: old timestamps cannot resolve cross-server clock skew.
-- New API writes always supply the exposure ID validated by TrackConversionService.
ALTER TABLE log_conversion
    MODIFY experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY variant VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD COLUMN impression_id BIGINT NULL,
    ADD CONSTRAINT fk_conversion_impression FOREIGN KEY (impression_id) REFERENCES log_impression(id);
