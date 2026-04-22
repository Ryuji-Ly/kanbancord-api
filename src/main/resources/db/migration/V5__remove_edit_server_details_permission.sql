-- Remove the EDIT_SERVER_DETAILS kanban permission.
-- Server details (name, icon, owner) are synced from Discord and cannot be
-- edited within KanbanCord, so this permission served no real purpose.

-- First remove any permission rules that reference it.
DELETE FROM permissions
WHERE kanban_permission_id IN (
    SELECT permission_id FROM kanban_permissions WHERE key = 'EDIT_SERVER_DETAILS'
);

-- Then remove the catalog entry itself.
DELETE FROM kanban_permissions WHERE key = 'EDIT_SERVER_DETAILS';

-- Update descriptions for remaining server-scoped permissions to clarify
-- their KanbanCord-specific meaning.
UPDATE kanban_permissions
SET description = 'Configure which subjects have which Kanban capabilities for this server'
WHERE key = 'EDIT_SERVER_PERMISSIONS';

UPDATE kanban_permissions
SET description = 'View Discord roles synced to this server and inspect their permission grants'
WHERE key = 'MANAGE_SERVER_ROLES';

UPDATE kanban_permissions
SET description = 'View server members and manage member-level permission overrides'
WHERE key = 'MANAGE_SERVER_MEMBERS';
