-- When the server was told that a board post can no longer be kept up to date because the bot lost
-- its permissions there; cleared once the post is redrawn again. Told once, not on every retry.
ALTER TABLE board_posts ADD COLUMN blocked_notice_at TIMESTAMPTZ;
