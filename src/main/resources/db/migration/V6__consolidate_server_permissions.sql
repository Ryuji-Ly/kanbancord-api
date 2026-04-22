-- Remove MANAGE_SERVER_ROLES and MANAGE_SERVER_MEMBERS — role assignment and
-- membership management are Discord-side only; these permissions have no
-- meaningful action surface in KanbanCord.
--
-- Rename EDIT_SERVER_PERMISSIONS → MANAGE_SERVER_PERMISSIONS for clarity.

-- Remove rules referencing the dropped permissions first.
DELETE FROM permissions
WHERE kanban_permission_id IN (
    SELECT permission_id FROM kanban_permissions
    WHERE key IN ('MANAGE_SERVER_ROLES', 'MANAGE_SERVER_MEMBERS')
);

-- Drop the catalog entries.
DELETE FROM kanban_permissions
WHERE key IN ('MANAGE_SERVER_ROLES', 'MANAGE_SERVER_MEMBERS');

-- Rename EDIT_SERVER_PERMISSIONS → MANAGE_SERVER_PERMISSIONS.
UPDATE kanban_permissions
SET key         = 'MANAGE_SERVER_PERMISSIONS',
    name        = 'Manage Server Permissions',
    description = 'Configure Kanban permission rules for this server (subjects, states, overrides)'
WHERE key = 'EDIT_SERVER_PERMISSIONS';
