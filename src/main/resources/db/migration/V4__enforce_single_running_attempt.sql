CREATE UNIQUE INDEX uq_task_attempts_one_running_per_task
    ON task_attempts (task_id)
    WHERE status = 'RUNNING';
