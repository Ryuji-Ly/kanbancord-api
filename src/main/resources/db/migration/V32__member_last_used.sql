-- Only people who use KanbanCord in a server, or are picked for something there, are kept as its members; others
-- are looked up in Discord when needed. last_used_at is when the person last did something in the server, to tell
-- the two apart; a member never seen using it may be removed again once nothing refers to them.
ALTER TABLE server_members ADD COLUMN last_used_at TIMESTAMP;

-- What people did before this column existed counts as use.
UPDATE server_members sm SET last_used_at = now()
WHERE sm.user_id IN (SELECT owner_id FROM servers WHERE server_id = sm.server_id)
   OR EXISTS (SELECT 1 FROM audit_log a WHERE a.server_id = sm.server_id AND a.user_id = sm.user_id)
   OR EXISTS (SELECT 1 FROM boards b WHERE b.server_id = sm.server_id AND b.created_by = sm.user_id)
   OR EXISTS (SELECT 1 FROM tasks t JOIN boards b ON b.board_id = t.board_id
              WHERE b.server_id = sm.server_id AND t.created_by = sm.user_id)
   OR EXISTS (SELECT 1 FROM task_comments c JOIN tasks t ON t.task_id = c.task_id JOIN boards b ON b.board_id = t.board_id
              WHERE b.server_id = sm.server_id AND c.user_id = sm.user_id)
   OR EXISTS (SELECT 1 FROM user_sessions s WHERE s.user_id = sm.user_id);
