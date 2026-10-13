CREATE TABLE task_status_transitions (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES coding_tasks(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    reason TEXT,
    actor VARCHAR(64) NOT NULL,
    transitioned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_task_status_transitions_task_sequence UNIQUE (task_id, sequence_number)
);

CREATE INDEX idx_task_status_transitions_task_sequence
    ON task_status_transitions (task_id, sequence_number);

INSERT INTO task_status_transitions (
    id, task_id, sequence_number, from_status, to_status, reason, actor, transitioned_at
)
SELECT
    gen_random_uuid(), id, 1, NULL, status,
    'Baseline captured when task history was introduced', 'MIGRATION', created_at
FROM coding_tasks;
