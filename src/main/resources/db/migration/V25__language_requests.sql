-- Requests for the website in another language, delivered to the developers by the bot through the
-- notification queue. Every queued group now says what it is, and the database keeps the kinds
-- apart: a change can never be delivered as a request, nor a request as a change.

ALTER TABLE notification_queue
    ADD COLUMN kind VARCHAR(20),
    -- A request is not about a server.
    ALTER COLUMN server_id DROP NOT NULL,
    ADD COLUMN request_user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
    ADD COLUMN request_language VARCHAR(35),
    ADD COLUMN request_note VARCHAR(300);

UPDATE notification_queue SET kind = CASE WHEN reminder_kind IS NULL THEN 'CHANGES' ELSE 'REMINDER' END;

ALTER TABLE notification_queue
    ALTER COLUMN kind SET NOT NULL,
    ADD CONSTRAINT ck_notification_queue_kind CHECK (
        (kind = 'CHANGES'
            AND server_id IS NOT NULL AND cardinality(audit_ids) > 0
            AND reminder_kind IS NULL AND reminder_task_id IS NULL AND reminder_due IS NULL
            AND request_user_id IS NULL AND request_language IS NULL AND request_note IS NULL)
        OR (kind = 'REMINDER'
            AND server_id IS NOT NULL AND cardinality(audit_ids) = 0
            AND reminder_kind IS NOT NULL AND reminder_task_id IS NOT NULL AND reminder_due IS NOT NULL
            AND request_user_id IS NULL AND request_language IS NULL AND request_note IS NULL)
        OR (kind = 'LANGUAGE_REQUEST'
            AND server_id IS NULL AND cardinality(audit_ids) = 0
            AND reminder_kind IS NULL AND reminder_task_id IS NULL AND reminder_due IS NULL
            AND request_user_id IS NOT NULL AND request_language IS NOT NULL)
    );

-- For the limit on requests per person.
CREATE INDEX idx_notification_queue_requests ON notification_queue (request_user_id, created_at)
    WHERE kind = 'LANGUAGE_REQUEST';
