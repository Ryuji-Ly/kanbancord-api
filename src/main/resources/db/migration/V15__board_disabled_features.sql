-- Features a board has switched off for itself. A board can only narrow what its server has on:
-- a feature is on for a board when the server has it on and the board has not switched it off.
CREATE TABLE board_disabled_features (
    board_id BIGINT NOT NULL REFERENCES boards(board_id) ON DELETE CASCADE,
    feature VARCHAR(40) NOT NULL,
    PRIMARY KEY (board_id, feature)
);
