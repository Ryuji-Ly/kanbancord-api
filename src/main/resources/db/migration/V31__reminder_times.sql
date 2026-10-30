-- When people are reminded before a task is due, in minutes before the due date. The server's times apply to
-- every board unless the board has its own; each person can choose their own, everywhere or for one board.
-- An empty list means no reminders before the due date (the overdue reminder still comes).
ALTER TABLE servers ADD COLUMN reminder_minutes INTEGER[] NOT NULL DEFAULT '{1440}';
ALTER TABLE boards ADD COLUMN reminder_minutes INTEGER[];

-- A person's own times: board_id NULL for everywhere, a board's id for that board only.
CREATE TABLE user_reminder_times (
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    board_id BIGINT REFERENCES boards(board_id) ON DELETE CASCADE,
    minutes INTEGER[] NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_user_reminder_times ON user_reminder_times (user_id, COALESCE(board_id, 0));

-- Reminders for one person (their own times) next to the shared ones (user 0): the version before this one
-- records only shared reminders, which keep their meaning.
ALTER TABLE task_due_reminders ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE task_due_reminders DROP CONSTRAINT task_due_reminders_pkey;
ALTER TABLE task_due_reminders ADD PRIMARY KEY (task_id, kind, due_date, user_id);
ALTER TABLE notification_queue ADD COLUMN reminder_user_id BIGINT;
