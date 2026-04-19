-- Normalize previously seeded catalog keys into Kanban-domain keys.
-- This migration is forward-safe when V2 has already been applied in older environments.

UPDATE kanban_permissions SET key = 'EDIT_SERVER_DETAILS', name = 'Edit Server Details', description = 'Edit server-level details and settings', category = 'SERVER'
WHERE key = 'MANAGE_SERVER';

UPDATE kanban_permissions SET key = 'EDIT_SERVER_PERMISSIONS', name = 'Edit Server Permissions', description = 'Manage permission rules and defaults', category = 'SERVER'
WHERE key = 'MANAGE_PERMISSIONS';

UPDATE kanban_permissions SET key = 'MANAGE_SERVER_MEMBERS', name = 'Manage Server Members', description = 'Manage server membership and access', category = 'SERVER'
WHERE key = 'MANAGE_MEMBERS';

UPDATE kanban_permissions SET key = 'MANAGE_SERVER_ROLES', name = 'Manage Server Roles', description = 'Manage role sync and role assignments', category = 'SERVER'
WHERE key = 'BAN_MEMBERS';

UPDATE kanban_permissions SET key = 'EDIT_BOARD_DETAILS', name = 'Edit Board Details', description = 'Edit board name, description, and state', category = 'BOARD'
WHERE key = 'MANAGE_BOARD';

UPDATE kanban_permissions SET key = 'EDIT_COLUMN', name = 'Edit Columns', description = 'Edit column properties', category = 'COLUMN'
WHERE key = 'MANAGE_COLUMN';

UPDATE kanban_permissions SET key = 'ASSIGN_TASK', name = 'Assign Tasks', description = 'Assign and unassign task members', category = 'TASK'
WHERE key = 'ASSIGN_OTHERS';

UPDATE kanban_permissions SET key = 'CREATE_TASK_COMMENT', name = 'Create Task Comments', description = 'Create comments on tasks', category = 'COMMENT'
WHERE key = 'COMMENT_TASK';

-- Ensure old duplicate key does not conflict after ASSIGN_OTHERS -> ASSIGN_TASK rename.
DELETE FROM kanban_permissions kp
WHERE kp.key = 'ASSIGN_SELF'
  AND EXISTS (SELECT 1 FROM kanban_permissions keep WHERE keep.key = 'ASSIGN_TASK' AND keep.permission_id <> kp.permission_id);

-- Insert any new canonical keys missing from older environments.
INSERT INTO kanban_permissions (key, name, description, category, is_system)
VALUES
    ('VIEW_SERVER', 'View Server', 'View server overview and metadata', 'SERVER', TRUE),
    ('VIEW_TASK', 'View Tasks', 'View task details', 'TASK', TRUE),
    ('EDIT_BOARD_PERMISSIONS', 'Edit Board Permissions', 'Manage board-scoped permission overrides', 'BOARD', TRUE),
    ('ARCHIVE_BOARD', 'Archive Board', 'Archive and restore boards', 'BOARD', TRUE),
    ('DELETE_BOARD', 'Delete Board', 'Delete boards', 'BOARD', TRUE),
    ('DELETE_COLUMN', 'Delete Columns', 'Delete columns', 'COLUMN', TRUE),
    ('MOVE_COLUMN', 'Move Columns', 'Reorder columns within a board', 'COLUMN', TRUE),
    ('MOVE_TASK', 'Move Tasks', 'Move tasks between columns', 'TASK', TRUE),
    ('DELETE_TASK', 'Delete Tasks', 'Delete tasks', 'TASK', TRUE),
    ('EDIT_TASK_COMMENT', 'Edit Task Comments', 'Edit task comments', 'COMMENT', TRUE),
    ('DELETE_TASK_COMMENT', 'Delete Task Comments', 'Delete task comments', 'COMMENT', TRUE),
    ('CREATE_LABEL', 'Create Labels', 'Create labels', 'LABEL', TRUE),
    ('EDIT_LABEL', 'Edit Labels', 'Edit labels', 'LABEL', TRUE),
    ('DELETE_LABEL', 'Delete Labels', 'Delete labels', 'LABEL', TRUE),
    ('APPLY_LABEL_TO_TASK', 'Apply Labels To Tasks', 'Attach labels to tasks', 'LABEL', TRUE),
    ('REMOVE_LABEL_FROM_TASK', 'Remove Labels From Tasks', 'Detach labels from tasks', 'LABEL', TRUE)
ON CONFLICT (key) DO NOTHING;

UPDATE kanban_permissions
SET is_system = TRUE
WHERE key IN (
    'VIEW_SERVER',
    'EDIT_SERVER_DETAILS',
    'EDIT_SERVER_PERMISSIONS',
    'MANAGE_SERVER_ROLES',
    'MANAGE_SERVER_MEMBERS',
    'VIEW_AUDIT_LOG',
    'CREATE_BOARD',
    'VIEW_BOARD',
    'EDIT_BOARD_DETAILS',
    'EDIT_BOARD_PERMISSIONS',
    'ARCHIVE_BOARD',
    'DELETE_BOARD',
    'CREATE_COLUMN',
    'EDIT_COLUMN',
    'DELETE_COLUMN',
    'MOVE_COLUMN',
    'CREATE_TASK',
    'VIEW_TASK',
    'EDIT_TASK',
    'MOVE_TASK',
    'DELETE_TASK',
    'ARCHIVE_TASK',
    'ASSIGN_TASK',
    'CREATE_TASK_COMMENT',
    'EDIT_TASK_COMMENT',
    'DELETE_TASK_COMMENT',
    'CREATE_LABEL',
    'EDIT_LABEL',
    'DELETE_LABEL',
    'APPLY_LABEL_TO_TASK',
    'REMOVE_LABEL_FROM_TASK'
);
