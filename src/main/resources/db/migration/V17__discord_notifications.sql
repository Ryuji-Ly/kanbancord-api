-- Discord notifications: where a server's updates go, what each person wants to hear about, and
-- the queue the bot delivers from.

-- The server's text channels, as the bot sees them, so the website can offer them by name.
CREATE TABLE discord_channels (
    channel_id BIGINT PRIMARY KEY,
    server_id BIGINT NOT NULL REFERENCES servers(server_id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(100),
    position INTEGER NOT NULL DEFAULT 0,
    -- Whether the bot may post there; a channel it cannot post in is shown but not usable.
    bot_can_post BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_discord_channels_server ON discord_channels (server_id);

-- One per server: the channel that mirrors the audit log (never pings anyone).
CREATE TABLE server_notification_settings (
    server_id BIGINT PRIMARY KEY REFERENCES servers(server_id) ON DELETE CASCADE,
    audit_channel_id BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Update feeds: a channel, which boards (none listed: the whole server), which events, and when
-- to mention the people involved.
CREATE TABLE notification_feeds (
    feed_id BIGSERIAL PRIMARY KEY,
    server_id BIGINT NOT NULL REFERENCES servers(server_id) ON DELETE CASCADE,
    channel_id BIGINT NOT NULL,
    board_ids BIGINT[] NOT NULL DEFAULT '{}',
    -- {"TASK_CREATED": true, ...}; events left out are off.
    events JSONB NOT NULL DEFAULT '{}',
    -- {"PEOPLE": true, ...}: per category, whether the people involved are mentioned.
    mentions JSONB NOT NULL DEFAULT '{}',
    mention_roles BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_feeds_server ON notification_feeds (server_id);

-- What each person wants by direct message; missing settings mean the defaults.
CREATE TABLE user_notification_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    settings JSONB NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Changes waiting to be delivered, grouped: changes to the same task within a short while are
-- delivered together, as one message. The bot claims due groups, delivers them and says so.
CREATE TABLE notification_queue (
    batch_id BIGSERIAL PRIMARY KEY,
    server_id BIGINT NOT NULL REFERENCES servers(server_id) ON DELETE CASCADE,
    group_key VARCHAR(100) NOT NULL,
    -- Audit log entries, in the order they happened.
    audit_ids BIGINT[] NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deliver_after TIMESTAMP NOT NULL,
    claimed_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    delivered_at TIMESTAMP
);

-- At most one open group per key; later changes join it until it is claimed.
CREATE UNIQUE INDEX uk_notification_queue_open_group ON notification_queue (group_key) WHERE claimed_at IS NULL;
CREATE INDEX idx_notification_queue_due ON notification_queue (deliver_after) WHERE delivered_at IS NULL;
