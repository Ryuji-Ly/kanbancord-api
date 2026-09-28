-- Board posts: a message in a Discord channel or thread showing a whole board, which the bot edits
-- whenever the board changes.
CREATE TABLE board_posts (
    post_id BIGSERIAL PRIMARY KEY,
    server_id BIGINT NOT NULL REFERENCES servers(server_id) ON DELETE CASCADE,
    -- Not a foreign key: when the board is deleted the post stays, so the bot can say so in the
    -- message before the post is removed.
    board_id BIGINT NOT NULL,
    -- The channel or thread the message is in.
    channel_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL UNIQUE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Since when the message is out of date; null when it shows the board as it is.
    dirty_at TIMESTAMPTZ,
    -- When the bot took it to redraw; a claim not reported back in time is taken again.
    claimed_at TIMESTAMPTZ,
    -- After a failed redraw: not before this, and given up on if it keeps failing.
    retry_after TIMESTAMPTZ,
    failing_since TIMESTAMPTZ
);

CREATE INDEX idx_board_posts_board ON board_posts (board_id);
CREATE INDEX idx_board_posts_server ON board_posts (server_id);
CREATE INDEX idx_board_posts_pending ON board_posts (dirty_at) WHERE dirty_at IS NOT NULL;
