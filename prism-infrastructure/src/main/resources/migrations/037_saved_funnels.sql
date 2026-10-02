-- Apply once after 036 with API/Admin writes stopped and the database backed up.
-- Saved analysis criteria do not change experiment assignment or collected events.
-- UTC query bounds use DATETIME so the supported 1970-01-01T00:00:00 lower bound is representable.
CREATE TABLE saved_funnels (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id BIGINT NOT NULL,
    name VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    description VARCHAR(1000) NOT NULL,
    steps_json LONGTEXT NOT NULL,
    window_hours INT NOT NULL,
    period_mode VARCHAR(16) NOT NULL,
    from_at DATETIME(6) NULL,
    until_at DATETIME(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE INDEX uk_saved_funnel_name (experiment_id, name),
    INDEX idx_saved_funnel_experiment (experiment_id, id),
    CONSTRAINT fk_saved_funnel_experiment FOREIGN KEY (experiment_id) REFERENCES experiments(id) ON DELETE CASCADE
);
