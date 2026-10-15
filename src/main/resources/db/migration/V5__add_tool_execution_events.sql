CREATE TABLE tool_execution_events (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES coding_tasks(id) ON DELETE CASCADE,
    attempt_id UUID NOT NULL REFERENCES task_attempts(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    arguments_summary VARCHAR(500) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    duration_millis BIGINT NOT NULL,
    result_summary VARCHAR(300) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_tool_execution_events_attempt_sequence UNIQUE (attempt_id, sequence_number),
    CONSTRAINT ck_tool_execution_events_positive_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_tool_execution_events_nonnegative_duration CHECK (duration_millis >= 0),
    CONSTRAINT ck_tool_execution_events_outcome CHECK (outcome IN ('STARTED', 'SUCCEEDED', 'FAILED', 'REJECTED'))
);

CREATE INDEX idx_tool_execution_events_task_attempt_sequence
    ON tool_execution_events (task_id, attempt_id, sequence_number);
