CREATE TABLE task_comment_edits (
    edit_id BIGSERIAL PRIMARY KEY,
    comment_id BIGINT NOT NULL,
    editor_user_id BIGINT NOT NULL,
    edited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_comment_edits_comment FOREIGN KEY (comment_id) REFERENCES task_comments(comment_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_comment_edits_user FOREIGN KEY (editor_user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_task_comment_edits_comment_id ON task_comment_edits(comment_id);
CREATE INDEX idx_task_comment_edits_editor_user_id ON task_comment_edits(editor_user_id);
