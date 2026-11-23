-- Following a whole board: its followers hear about every task on it as if they followed each one.
CREATE TABLE board_followers (
    board_id BIGINT NOT NULL REFERENCES boards(board_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (board_id, user_id)
);

CREATE INDEX idx_board_followers_user ON board_followers (user_id);
