-- Apply once after 035 with API/Admin writes stopped and the database backed up.
-- Existing events and SDK contracts are unchanged.
-- Prefix only the index, not the identity column: three full utf8mb4 VARCHAR(255)
-- values plus time/id exceed InnoDB's 3072-byte index-key limit.
ALTER TABLE log_impression ADD INDEX idx_impression_journey (experiment_key, user_id, variant(191), timestamp, id);
ALTER TABLE log_conversion ADD INDEX idx_conversion_journey (experiment_key, user_id, variant(191), timestamp, id);
