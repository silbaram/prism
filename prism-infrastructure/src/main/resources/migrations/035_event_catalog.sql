-- Apply once after 034 with API/Admin writes stopped and the database backed up.
-- Catalog registration is optional; previously collected events remain discoverable.
CREATE TABLE event_definitions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    description VARCHAR(1000) NOT NULL,
    UNIQUE INDEX uk_event_definition_name (event_name)
);

ALTER TABLE log_conversion ADD INDEX idx_conversion_event_name (event_name);
