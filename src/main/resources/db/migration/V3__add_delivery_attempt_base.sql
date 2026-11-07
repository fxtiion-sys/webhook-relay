ALTER TABLE deliveries
    ADD COLUMN attempt_base integer NOT NULL DEFAULT 0;

ALTER TABLE deliveries
    ADD CONSTRAINT chk_deliveries_attempt_base
        CHECK (
            attempt_base >= 0
                AND attempt_base <= attempt_count
            );