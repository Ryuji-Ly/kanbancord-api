-- Boards now inherit the server's permission rules; board-scoped rows are overrides only.
--
-- Until now every new board received a copy of every server rule. Under the new resolution model
-- (board overrides server) those copies would pin boards to the server rules as they were when the
-- board was created. Remove every board rule that still matches its server rule exactly (same
-- subject, same permission, same state): it changes nothing today and would only block future
-- server-level changes from reaching the board.
--
-- Board rules that differ from the server (deliberate overrides, or copies whose server rule has
-- since changed) are kept and continue to apply as overrides.

DELETE FROM permissions board_rule
USING boards b, permissions server_rule
WHERE board_rule.scope_type = 'BOARD'
  AND b.board_id = board_rule.scope_id
  AND server_rule.scope_type = 'SERVER'
  AND server_rule.scope_id = b.server_id
  AND server_rule.subject_type = board_rule.subject_type
  AND server_rule.subject_id = board_rule.subject_id
  AND server_rule.kanban_permission_id = board_rule.kanban_permission_id
  AND UPPER(server_rule.state) = UPPER(board_rule.state);
