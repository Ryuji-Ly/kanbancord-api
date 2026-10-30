-- Suggestions to switch on a feature a board is doing by hand (tags in titles for labels, and so on) are
-- worked out from the board when asked. Only a dismissal is kept: per board and feature, not per person.
CREATE TABLE board_feature_suggestion_dismissals (
    board_id BIGINT NOT NULL REFERENCES boards(board_id) ON DELETE CASCADE,
    feature VARCHAR(32) NOT NULL,
    dismissed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, feature)
);
