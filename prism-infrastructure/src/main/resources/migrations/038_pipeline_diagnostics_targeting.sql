-- Apply once after 037, with API/Admin/workers stopped. Historical outbox ages are unknown.
ALTER TABLE pipeline_outbox ADD COLUMN created_at TIMESTAMP(6) NULL;
CREATE INDEX idx_pipeline_outbox_created ON pipeline_outbox (created_at);
CREATE TABLE targeting_attributes (
    attribute_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    value_type VARCHAR(16) NOT NULL,
    description VARCHAR(1000) NOT NULL
);
