CREATE TABLE task_attempts (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES coding_tasks(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    error_summary TEXT,
    report_json TEXT,
    CONSTRAINT uq_task_attempts_task_number UNIQUE (task_id, attempt_number),
    CONSTRAINT ck_task_attempts_positive_number CHECK (attempt_number > 0),
    CONSTRAINT ck_task_attempts_finished_status CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (status <> 'RUNNING' AND finished_at IS NOT NULL)
    )
);

CREATE INDEX idx_task_attempts_task_number
    ON task_attempts (task_id, attempt_number);

