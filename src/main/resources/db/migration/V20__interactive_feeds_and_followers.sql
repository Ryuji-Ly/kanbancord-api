-- Interactive feeds: posts that show the whole task, with buttons to change it. Feeds made before
-- this keep their plain posts; new feeds are interactive unless switched off.
ALTER TABLE notification_feeds ADD COLUMN interactive BOOLEAN NOT NULL DEFAULT FALSE;

-- People following a task hear about it by direct message, like those assigned to it.
CREATE TABLE task_followers (
    task_id BIGINT NOT NULL REFERENCES tasks(task_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, user_id)
);

CREATE INDEX idx_task_followers_user ON task_followers (user_id);
