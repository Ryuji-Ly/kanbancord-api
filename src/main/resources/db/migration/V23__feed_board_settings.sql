-- A board's own settings for a feed that covers it: the events it posts and the events that mention
-- people, where they differ from the feed's. Only the differences are kept; everything else follows
-- the feed.
CREATE TABLE feed_board_settings (
    feed_id BIGINT NOT NULL REFERENCES notification_feeds(feed_id) ON DELETE CASCADE,
    board_id BIGINT NOT NULL REFERENCES boards(board_id) ON DELETE CASCADE,
    events JSONB NOT NULL DEFAULT '{}',
    mentions JSONB NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (feed_id, board_id)
);
