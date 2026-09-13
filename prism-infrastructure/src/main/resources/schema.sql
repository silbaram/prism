-- MySQL 8.0.17+. Identity strings use exact, case-sensitive, NO PAD comparisons.
-- 1. 실험 테이블
CREATE TABLE IF NOT EXISTS experiments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    goal_event_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_experiment_key (experiment_key),
    INDEX idx_status (status)
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
