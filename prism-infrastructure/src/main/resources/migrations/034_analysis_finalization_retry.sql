-- Apply once after 033, before deploying the upgraded API/Admin.
ALTER TABLE analysis_observations
    ADD COLUMN finalization_retry_at TIMESTAMP(6) NULL,
    ADD COLUMN finalization_attempts INT NOT NULL DEFAULT 0;
