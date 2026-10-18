-- Posting a board again in the same channel replaces the older posts there: only the newest keeps
-- updating. An older post is redrawn once more, to say so and link to the new one, then forgotten.
-- This is the new message it points to.
ALTER TABLE board_posts ADD COLUMN replaced_by_message_id BIGINT;

-- Posts already repeated in a channel: all but the newest are replaced by it.
UPDATE board_posts p
SET replaced_by_message_id = newest.message_id, dirty_at = now() - interval '1 minute', claimed_at = NULL
FROM (
    SELECT DISTINCT ON (board_id, channel_id) board_id, channel_id, post_id, message_id
    FROM board_posts
    ORDER BY board_id, channel_id, post_id DESC
) newest
WHERE p.board_id = newest.board_id AND p.channel_id = newest.channel_id AND p.post_id <> newest.post_id;
