-- Comments are only edited by their author now, so the permission to edit other people's comments
-- is gone. Rules that granted or denied it go with it (permissions cascade on delete).
DELETE FROM kanban_permissions WHERE key = 'EDIT_TASK_COMMENT';
