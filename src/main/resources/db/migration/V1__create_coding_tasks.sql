CREATE TABLE coding_tasks (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    repository_url VARCHAR(2048),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE task_acceptance_criteria (
    task_id UUID NOT NULL REFERENCES coding_tasks(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    criterion TEXT NOT NULL,
    PRIMARY KEY (task_id, position)
);
