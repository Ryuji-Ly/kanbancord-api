-- Files uploaded to Imgur through KanbanCord. The file lives on Imgur; this keeps who uploaded it,
-- where, and the delete hash Imgur returns only once, so the file can be taken down later.
CREATE TABLE media_uploads (
    upload_id BIGSERIAL PRIMARY KEY,
    server_id BIGINT NOT NULL REFERENCES servers(server_id) ON DELETE CASCADE,
    -- Kept after the board is deleted, so its files can still be found and taken down.
    board_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(user_id),
    kind VARCHAR(10) NOT NULL,
    content_type VARCHAR(40) NOT NULL,
    size_bytes BIGINT NOT NULL,
    imgur_id VARCHAR(40) NOT NULL,
    link VARCHAR(255) NOT NULL,
    delete_hash VARCHAR(80),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_media_uploads_server ON media_uploads (server_id, created_at);
