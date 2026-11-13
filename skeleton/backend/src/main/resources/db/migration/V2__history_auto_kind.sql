-- History kind AUTO: written when an AUTO step has run and routes on (Req 12.13).
ALTER TABLE history_entry DROP CONSTRAINT ck_history_kind;
ALTER TABLE history_entry ADD CONSTRAINT ck_history_kind
    CHECK (kind IN ('START', 'ACTION', 'SKIPPED', 'AUTO', 'EVENT_DEFERRED', 'EVENT_FAILED', 'EXPRESSION_ERROR'));
