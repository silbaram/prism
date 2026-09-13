-- Apply once after 029, with API/Admin writes stopped. MySQL 8.0.17+.
ALTER TABLE experiments ADD COLUMN configuration_locked BOOLEAN NOT NULL DEFAULT FALSE;

-- Historical definitions cannot be reconstructed. Freeze the currently stored configuration.
UPDATE experiments e SET configuration_locked = TRUE
WHERE status <> 'DRAFT' OR EXISTS (SELECT 1 FROM log_impression i WHERE i.experiment_key = e.experiment_key);

CREATE TABLE experiment_changes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id BIGINT NOT NULL,
    experiment_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    action VARCHAR(32) NOT NULL,
    before_snapshot LONGTEXT NULL,
    after_snapshot LONGTEXT NULL,
    changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_experiment_changes (experiment_id, id)
);
