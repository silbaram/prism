-- Apply once after 030 with API/Admin writes stopped. Times are UTC instants.
ALTER TABLE experiments
    ADD COLUMN traffic_allocation INT NOT NULL DEFAULT 100,
    ADD COLUMN starts_at TIMESTAMP(6) NULL,
    ADD COLUMN ends_at TIMESTAMP(6) NULL,
    ADD INDEX idx_experiment_schedule (status, starts_at, ends_at);
ALTER TABLE experiment_changes ADD COLUMN actor VARCHAR(255) NULL;
CREATE TABLE experiment_guardrails (
    experiment_id BIGINT NOT NULL,
    event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (experiment_id, event_name),
    CONSTRAINT fk_guardrail_experiment FOREIGN KEY (experiment_id) REFERENCES experiments(id) ON DELETE CASCADE
);
