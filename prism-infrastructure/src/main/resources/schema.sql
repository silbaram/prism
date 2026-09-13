CREATE TABLE IF NOT EXISTS experiment_layers (
    layer_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

-- MySQL 8.0.17+. Identity strings use exact, case-sensitive, NO PAD comparisons.
-- 1. 실험 테이블
CREATE TABLE IF NOT EXISTS experiments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    goal_event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    status VARCHAR(50) NOT NULL,
    configuration_locked BOOLEAN NOT NULL DEFAULT FALSE,
    layer_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    layer_start INT NULL,
    layer_end INT NULL,
    sticky_bucketing BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_experiment_layer (layer_key, layer_start, layer_end),
    CONSTRAINT fk_experiment_layer FOREIGN KEY (layer_key) REFERENCES experiment_layers(layer_key),
    traffic_allocation INT NOT NULL DEFAULT 100,
    starts_at TIMESTAMP(6) NULL,
    ends_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_experiment_key (experiment_key),
    INDEX idx_status (status),
    INDEX idx_experiment_schedule (status, starts_at, ends_at)
);

CREATE TABLE IF NOT EXISTS population_policy (
    id BIGINT PRIMARY KEY,
    holdout_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    holdout_basis_points INT NULL
);
INSERT IGNORE INTO population_policy (id, holdout_key, holdout_basis_points) VALUES (1, 'global-v1', NULL);

CREATE TABLE IF NOT EXISTS sticky_assignments (
    id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
);

-- 2. 변형 테이블
CREATE TABLE IF NOT EXISTS variants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    weight INT NOT NULL,
    experiment_id BIGINT NOT NULL,
    FOREIGN KEY (experiment_id) REFERENCES experiments(id) ON DELETE CASCADE,
    INDEX idx_experiment_id (experiment_id)
);

-- 3. 타겟팅 규칙 테이블
CREATE TABLE IF NOT EXISTS targeting_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_expression TEXT NOT NULL,
    experiment_id BIGINT,
    FOREIGN KEY (experiment_id) REFERENCES experiments(id) ON DELETE CASCADE,
    INDEX idx_experiment_id (experiment_id)
);

-- 4. 노출 로그 테이블
CREATE TABLE IF NOT EXISTS log_impression (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    timestamp TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    UNIQUE INDEX uk_impression_event_id (event_id),
    INDEX idx_experiment_key (experiment_key),
    INDEX idx_user_experiment (user_id, experiment_key, id),
    INDEX idx_user_experiment_occurred (user_id, experiment_key, timestamp, event_id, id)
);

-- 5. 전환 로그 테이블
CREATE TABLE IF NOT EXISTS log_conversion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    impression_id BIGINT NULL,
    event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    timestamp TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    UNIQUE INDEX uk_conversion_event_id (event_id),
    INDEX idx_experiment_key (experiment_key),
    CONSTRAINT fk_conversion_impression FOREIGN KEY (impression_id) REFERENCES log_impression(id),
    INDEX idx_variant (variant),
    INDEX idx_user_id (user_id),
    INDEX idx_conversion_goal (experiment_key, event_name, variant)
);

CREATE TABLE IF NOT EXISTS event_receipts (
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    payload_hash VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    config_version VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
);

CREATE TABLE IF NOT EXISTS experiment_changes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id BIGINT NOT NULL,
    experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    action VARCHAR(32) NOT NULL,
    before_snapshot LONGTEXT NULL,
    after_snapshot LONGTEXT NULL,
    changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    actor VARCHAR(255) NULL,
    INDEX idx_experiment_changes (experiment_id, id)
);

CREATE TABLE IF NOT EXISTS experiment_guardrails (
    experiment_id BIGINT NOT NULL,
    event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (experiment_id, event_name),
    CONSTRAINT fk_guardrail_experiment FOREIGN KEY (experiment_id) REFERENCES experiments(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS pipeline_inbox (
    id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    retry_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    INDEX idx_pipeline_due (status, retry_at, id)
);
CREATE TABLE IF NOT EXISTS pipeline_outbox (
    id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    payload LONGTEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS population_exposures (
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
    cohort_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    INDEX idx_population_exposures (cohort_key, variant, user_id)
);
CREATE TABLE IF NOT EXISTS population_conversions (
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

CREATE TABLE IF NOT EXISTS analysis_plans (
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
CREATE TABLE IF NOT EXISTS analysis_observations (
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
