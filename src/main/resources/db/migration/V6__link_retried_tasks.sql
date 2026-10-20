ALTER TABLE coding_tasks
    ADD COLUMN retry_of UUID REFERENCES coding_tasks(id) ON DELETE SET NULL;
