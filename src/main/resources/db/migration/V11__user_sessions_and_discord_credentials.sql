-- Sign-in sessions. Each browser sign-in gets one; its refresh token lives in an httpOnly cookie and
-- only its SHA-256 hash is stored. Access tokens name their session, so revoking it signs them out.
CREATE TABLE user_sessions (
    session_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    -- The token replaced by the latest refresh; accepted briefly so tabs refreshing at once do not
    -- sign each other out, and treated as theft after that.
    previous_refresh_token_hash VARCHAR(64),
    refreshed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    user_agent VARCHAR(512)
);

CREATE INDEX idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX idx_user_sessions_previous_hash ON user_sessions(previous_refresh_token_hash);

-- The user's Discord OAuth tokens, encrypted with AES-GCM. Kept only while the user has an active
-- session, so the browser never needs to hold a Discord token.
CREATE TABLE discord_credentials (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    access_token BYTEA NOT NULL,
    refresh_token BYTEA,
    expires_at TIMESTAMPTZ NOT NULL,
    scope VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL
);
