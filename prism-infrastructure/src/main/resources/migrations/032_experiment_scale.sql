-- Apply once after 031 with API/Admin writes stopped and all SDKs upgraded first.
CREATE TABLE population_policy (
    id BIGINT PRIMARY KEY,
    holdout_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    holdout_basis_points INT NULL
);
INSERT INTO population_policy (id, holdout_key, holdout_basis_points) VALUES (1, 'global-v1', NULL);
CREATE TABLE experiment_layers (
    layer_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);
ALTER TABLE experiments
    ADD COLUMN layer_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN layer_start INT NULL,
    ADD COLUMN layer_end INT NULL,
    ADD COLUMN sticky_bucketing BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX idx_experiment_layer (layer_key, layer_start, layer_end),
    ADD CONSTRAINT fk_experiment_layer FOREIGN KEY (layer_key) REFERENCES experiment_layers(layer_key);
CREATE TABLE sticky_assignments (
    id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
);

CREATE TABLE pipeline_inbox (
    id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    retry_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    INDEX idx_pipeline_due (status, retry_at, id)
);
CREATE TABLE pipeline_outbox (
    id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    payload LONGTEXT NOT NULL
);
CREATE TABLE population_exposures (
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    cohort_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    INDEX idx_population_exposures (cohort_key, variant, user_id)
);
CREATE TABLE population_conversions (
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    cohort_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    exposure_event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    INDEX idx_population_outcomes (cohort_key, variant, user_id),
    CONSTRAINT fk_population_exposure FOREIGN KEY (exposure_event_id) REFERENCES population_exposures(event_id)
);
