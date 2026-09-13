-- Apply once after 027 and 028, with API/Admin writes stopped. MySQL 8.0.17+.
-- Historical/legacy events retain NULL IDs; existing attribution and aggregate queries stay valid.
ALTER TABLE log_impression
    MODIFY COLUMN timestamp TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD UNIQUE INDEX uk_impression_event_id (event_id),
    ADD INDEX idx_user_experiment_occurred (user_id, experiment_key, timestamp, event_id, id);
ALTER TABLE log_conversion
    MODIFY COLUMN timestamp TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD UNIQUE INDEX uk_conversion_event_id (event_id);
CREATE TABLE event_receipts (
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    payload_hash VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    config_version VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
);
