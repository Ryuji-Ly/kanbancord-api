-- Open permissions: everyone who can talk in the server may do everything to boards, columns and
-- tasks, without permission checks; only managing the server stays with its managers. Off unless a
-- manager turns it on.
ALTER TABLE servers ADD COLUMN open_permissions BOOLEAN NOT NULL DEFAULT FALSE;
