-- Columns a board marks as done. When none is marked, the board's last column counts as done, as it always did.
ALTER TABLE columns ADD COLUMN counts_as_done BOOLEAN NOT NULL DEFAULT FALSE;
