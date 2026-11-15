-- Comments on the public roadmap, sent by direct message to the people taking part in that issue: its reporter and
-- earlier commenters, who are usually not members of the roadmap's server. Each queued reply is for one person.

ALTER TABLE notification_queue
    ADD COLUMN comment_id BIGINT REFERENCES task_comments(comment_id) ON DELETE CASCADE;

ALTER TABLE notification_queue DROP CONSTRAINT ck_notification_queue_kind;
ALTER TABLE notification_queue
    ADD CONSTRAINT ck_notification_queue_kind CHECK (
        (kind = 'CHANGES'
            AND server_id IS NOT NULL AND cardinality(audit_ids) > 0
            AND reminder_kind IS NULL AND reminder_task_id IS NULL AND reminder_due IS NULL
            AND request_user_id IS NULL AND request_language IS NULL AND request_note IS NULL AND comment_id IS NULL)
        OR (kind = 'REMINDER'
            AND server_id IS NOT NULL AND cardinality(audit_ids) = 0
            AND reminder_kind IS NOT NULL AND reminder_task_id IS NOT NULL AND reminder_due IS NOT NULL
            AND request_user_id IS NULL AND request_language IS NULL AND request_note IS NULL AND comment_id IS NULL)
        OR (kind = 'LANGUAGE_REQUEST'
            AND server_id IS NULL AND cardinality(audit_ids) = 0
            AND reminder_kind IS NULL AND reminder_task_id IS NULL AND reminder_due IS NULL
            AND request_user_id IS NOT NULL AND request_language IS NOT NULL AND comment_id IS NULL)
        -- request_user_id is who the reply goes to.
        OR (kind = 'ROADMAP_REPLY'
            AND server_id IS NULL AND cardinality(audit_ids) = 0
            AND reminder_kind IS NULL AND reminder_task_id IS NULL AND reminder_due IS NULL
            AND request_user_id IS NOT NULL AND request_language IS NULL AND request_note IS NULL
            AND comment_id IS NOT NULL)
    );
