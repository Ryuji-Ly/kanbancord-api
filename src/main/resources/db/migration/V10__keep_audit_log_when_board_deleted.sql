-- Deleting a board used to delete its audit history, including the record of the deletion itself.
-- Keep the entries and detach them from the board instead; the board id stays in their changes.
ALTER TABLE audit_log DROP CONSTRAINT fk_audit_log_board;
ALTER TABLE audit_log
    ADD CONSTRAINT fk_audit_log_board FOREIGN KEY (board_id) REFERENCES boards(board_id) ON DELETE SET NULL;

-- Audit logs are listed newest first per server.
CREATE INDEX idx_audit_log_server_created_at ON audit_log(server_id, created_at DESC);
