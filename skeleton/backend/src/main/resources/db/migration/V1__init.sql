-- V1__init.sql: initial schema for the Skeleton Backend.
-- Statuses are plain varchar (Req 20.8). Records are jsonb (Req 7.17).

CREATE TABLE definition_version (
    id            uuid PRIMARY KEY,
    app_code      varchar(128) NOT NULL,
    version       integer      NOT NULL CHECK (version >= 1),
    status        varchar(16)  NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    content_hash  varchar(64)  NOT NULL,
    document      jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    published_at  timestamptz,
    CONSTRAINT uq_definition_app_version UNIQUE (app_code, version)
);
CREATE INDEX idx_definition_content_hash ON definition_version (content_hash);

CREATE TABLE app_user (
    id             uuid PRIMARY KEY,
    username       varchar(256) NOT NULL,
    password_hash  varchar(100) NOT NULL,
    display_name   varchar(256),
    active         boolean      NOT NULL DEFAULT true,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_username UNIQUE (username)
);

CREATE TABLE user_role (
    user_id    uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_code  varchar(128) NOT NULL,
    PRIMARY KEY (user_id, role_code)
);
CREATE INDEX idx_user_role_role ON user_role (role_code);

CREATE TABLE record (
    id            uuid PRIMARY KEY,
    app_code      varchar(128) NOT NULL,
    version       integer      NOT NULL,
    entity_code   varchar(128) NOT NULL,
    reference_no  varchar(64),
    data          jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_by    uuid,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_record_reference UNIQUE (app_code, reference_no)
);
CREATE INDEX idx_record_entity ON record (app_code, entity_code, created_at);
CREATE INDEX idx_record_data_gin ON record USING GIN (data);

CREATE TABLE reference_counter (
    prefix      varchar(64) NOT NULL,
    year        integer     NOT NULL,
    next_value  bigint      NOT NULL,
    PRIMARY KEY (prefix, year)
);

CREATE TABLE workflow_instance (
    id               uuid PRIMARY KEY,
    record_id        uuid         NOT NULL REFERENCES record (id),
    app_code         varchar(128) NOT NULL,
    version          integer      NOT NULL,
    workflow_code    varchar(128) NOT NULL,
    current_step     varchar(128) NOT NULL,
    status           varchar(128) NOT NULL,
    ended            boolean      NOT NULL DEFAULT false,
    assignee         uuid,
    due_at           timestamptz,
    step_entered_at  timestamptz  NOT NULL,
    started_by       uuid,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    lock_version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_instance_record UNIQUE (record_id)
);
CREATE INDEX idx_instance_assignee ON workflow_instance (assignee) WHERE ended = false;
CREATE INDEX idx_instance_step ON workflow_instance (app_code, workflow_code, current_step);

CREATE TABLE assignment_cursor (
    app_code       varchar(128) NOT NULL,
    workflow_code  varchar(128) NOT NULL,
    step_code      varchar(128) NOT NULL,
    last_user_id   uuid,
    PRIMARY KEY (app_code, workflow_code, step_code)
);

CREATE TABLE history_entry (
    seq           bigserial PRIMARY KEY,
    instance_id   uuid         NOT NULL REFERENCES workflow_instance (id),
    event_time    timestamptz  NOT NULL DEFAULT now(),
    kind          varchar(64)  NOT NULL
        CONSTRAINT ck_history_kind CHECK (kind IN ('START', 'ACTION', 'SKIPPED', 'EVENT_DEFERRED', 'EVENT_FAILED', 'EXPRESSION_ERROR')),
    actor_id      uuid,
    from_step     varchar(128),
    to_step       varchar(128),
    action_code   varchar(128),
    from_status   varchar(128),
    to_status     varchar(128),
    time_in_step_ms bigint CHECK (time_in_step_ms >= 0),
    remarks       varchar(2000),
    attachment    text,
    details       jsonb
);
CREATE INDEX idx_history_instance ON history_entry (instance_id, event_time, seq);

-- History is append-only (Req 12.7).
CREATE FUNCTION history_entry_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'history_entry is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_history_entry_no_update
    BEFORE UPDATE OR DELETE ON history_entry
    FOR EACH ROW EXECUTE FUNCTION history_entry_immutable();

CREATE TABLE audit_log (
    id           bigserial PRIMARY KEY,
    event_time   timestamptz  NOT NULL DEFAULT now(),
    actor_id     uuid,
    action       varchar(64)  NOT NULL,
    target_type  varchar(64)  NOT NULL,
    target_id    varchar(128),
    details      jsonb
);
CREATE INDEX idx_audit_target ON audit_log (target_type, target_id);
