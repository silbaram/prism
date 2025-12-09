-- 1. 실험 테이블
CREATE TABLE IF NOT EXISTS experiments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_key VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_experiment_key (experiment_key),
    INDEX idx_status (status)
);

-- 2. 변형 테이블
CREATE TABLE IF NOT EXISTS variants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
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
    experiment_key VARCHAR(255) NOT NULL,
    variant VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_experiment_key (experiment_key),
    INDEX idx_user_experiment (user_id, experiment_key, timestamp)
);

-- 5. 전환 로그 테이블
CREATE TABLE IF NOT EXISTS log_conversion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_key VARCHAR(255) NOT NULL,
    variant VARCHAR(255) NULL,
    user_id VARCHAR(255) NOT NULL,
    event_name VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_experiment_key (experiment_key),
    INDEX idx_variant (variant),
    INDEX idx_user_id (user_id)
);
