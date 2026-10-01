-- A thread per task, in one of the board's feed channels: switched on per board.
CREATE TABLE board_thread_settings (
    board_id BIGINT PRIMARY KEY REFERENCES boards(board_id) ON DELETE CASCADE,
    -- One of the channels with a feed covering the board; threads stop if no such feed is left.
    channel_id BIGINT NOT NULL,
    private_threads BOOLEAN NOT NULL DEFAULT FALSE,
    -- Where a task's updates go once it has a thread: BOTH, THREAD or CHANNEL.
    updates VARCHAR(16) NOT NULL DEFAULT 'BOTH',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Each task's thread, as the bot made it. Kept after the task is deleted, so the thread can be closed.
CREATE TABLE task_threads (
    task_id BIGINT PRIMARY KEY,
    server_id BIGINT NOT NULL REFERENCES servers(server_id) ON DELETE CASCADE,
    channel_id BIGINT NOT NULL,
    thread_id BIGINT NOT NULL,
    private_thread BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Whether the bot may make threads in a channel, as it does for posting.
ALTER TABLE discord_channels ADD COLUMN bot_can_thread BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE discord_channels ADD COLUMN bot_can_private_thread BOOLEAN NOT NULL DEFAULT FALSE;
