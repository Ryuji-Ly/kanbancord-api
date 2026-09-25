-- Reminders about due dates: once a task is due within a day, and once it is overdue. Recorded per
-- due date, so a task whose due date changes is reminded about the new one.
CREATE TABLE task_due_reminders (
    task_id BIGINT NOT NULL REFERENCES tasks(task_id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL,
    due_date TIMESTAMP NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, kind, due_date)
);

-- A queued reminder has no audit entries: nobody did anything, time passed.
ALTER TABLE notification_queue
    ADD COLUMN reminder_kind VARCHAR(20),
    ADD COLUMN reminder_task_id BIGINT,
    ADD COLUMN reminder_due TIMESTAMP;
