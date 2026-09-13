-- Apply once after 032 with API/Admin writes stopped. Analysis is opt-in for unstarted experiments.
CREATE TABLE analysis_plans (
    experiment_id BIGINT PRIMARY KEY,
    control_variant VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    outcome_hours INT NOT NULL,
    lateness_hours INT NOT NULL,
    segments_json TEXT NOT NULL,
    cuped_enabled BOOLEAN NOT NULL,
    baseline_cutoff TIMESTAMP(6) NULL,
    baseline_metric VARCHAR(255) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_analysis_plan_experiment FOREIGN KEY (experiment_id) REFERENCES experiments(id)
);
CREATE TABLE analysis_observations (
    id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    experiment_id BIGINT NOT NULL,
    user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    exposure_id BIGINT NOT NULL,
    exposed_at TIMESTAMP(6) NOT NULL,
    outcome_ends_at TIMESTAMP(6) NOT NULL,
    matures_at TIMESTAMP(6) NOT NULL,
    segments_json TEXT NOT NULL,
    baseline_value DOUBLE NULL,
    invalid_reason VARCHAR(64) NULL,
    converted BOOLEAN NULL,
    finalized_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_analysis_observation_plan FOREIGN KEY (experiment_id) REFERENCES analysis_plans(experiment_id),
    CONSTRAINT fk_analysis_observation_exposure FOREIGN KEY (exposure_id) REFERENCES log_impression(id),
    UNIQUE INDEX uk_analysis_user (experiment_id, user_id),
    INDEX idx_analysis_maturity (finalized_at, matures_at, id),
    INDEX idx_analysis_samples (experiment_id, id)
);
