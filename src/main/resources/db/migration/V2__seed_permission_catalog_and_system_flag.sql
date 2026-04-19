ALTER TABLE kanban_permissions
    ADD COLUMN IF NOT EXISTS is_system BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO kanban_permissions (key, name, description, category, is_system)
VALUES
    ('VIEW_SERVER', 'View Server', 'View server overview and metadata', 'SERVER', TRUE),
    ('EDIT_SERVER_DETAILS', 'Edit Server Details', 'Edit server-level details and settings', 'SERVER', TRUE),
    ('EDIT_SERVER_PERMISSIONS', 'Edit Server Permissions', 'Manage permission rules and defaults', 'SERVER', TRUE),
    ('MANAGE_SERVER_ROLES', 'Manage Server Roles', 'Manage role sync and role assignments', 'SERVER', TRUE),
    ('MANAGE_SERVER_MEMBERS', 'Manage Server Members', 'Manage server membership and access', 'SERVER', TRUE),
    ('VIEW_AUDIT_LOG', 'View Audit Log', 'Read audit events', 'SERVER', TRUE),

    ('CREATE_BOARD', 'Create Board', 'Create new boards', 'BOARD', TRUE),
    ('VIEW_BOARD', 'View Board', 'View board content', 'BOARD', TRUE),
    ('EDIT_BOARD_DETAILS', 'Edit Board Details', 'Edit board name, description, and state', 'BOARD', TRUE),
    ('EDIT_BOARD_PERMISSIONS', 'Edit Board Permissions', 'Manage board-scoped permission overrides', 'BOARD', TRUE),
    ('ARCHIVE_BOARD', 'Archive Board', 'Archive and restore boards', 'BOARD', TRUE),
    ('DELETE_BOARD', 'Delete Board', 'Delete boards', 'BOARD', TRUE),

    ('CREATE_COLUMN', 'Create Columns', 'Create board columns', 'COLUMN', TRUE),
    ('EDIT_COLUMN', 'Edit Columns', 'Edit column properties', 'COLUMN', TRUE),
    ('DELETE_COLUMN', 'Delete Columns', 'Delete columns', 'COLUMN', TRUE),
    ('MOVE_COLUMN', 'Move Columns', 'Reorder columns within a board', 'COLUMN', TRUE),

    ('CREATE_TASK', 'Create Tasks', 'Create tasks', 'TASK', TRUE),
    ('VIEW_TASK', 'View Tasks', 'View task details', 'TASK', TRUE),
    ('EDIT_TASK', 'Edit Tasks', 'Edit task fields', 'TASK', TRUE),
    ('MOVE_TASK', 'Move Tasks', 'Move tasks between columns', 'TASK', TRUE),
    ('DELETE_TASK', 'Delete Tasks', 'Delete tasks', 'TASK', TRUE),
    ('ARCHIVE_TASK', 'Archive Tasks', 'Archive and restore tasks', 'TASK', TRUE),
    ('ASSIGN_TASK', 'Assign Tasks', 'Assign and unassign task members', 'TASK', TRUE),

    ('CREATE_TASK_COMMENT', 'Create Task Comments', 'Create comments on tasks', 'COMMENT', TRUE),
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
