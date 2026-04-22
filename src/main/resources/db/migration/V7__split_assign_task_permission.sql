-- Replace ASSIGN_TASK with ASSIGN_TASK_SELF and ASSIGN_TASK_OTHERS.
-- Existing permission rules referencing ASSIGN_TASK are migrated to ASSIGN_TASK_OTHERS
-- (the broader of the two new permissions), then ASSIGN_TASK_SELF is inserted as a new catalog entry.

-- 1. Rename the existing catalog entry to ASSIGN_TASK_OTHERS
UPDATE kanban_permissions
SET key         = 'ASSIGN_TASK_OTHERS',
    name        = 'Assign Tasks to Others',
    description = 'Assign other members to a task'
WHERE key = 'ASSIGN_TASK';

-- 2. Insert the new ASSIGN_TASK_SELF entry (will be picked up by bootstrap catalog seed)
INSERT INTO kanban_permissions (key, name, description, category, is_system)
VALUES ('ASSIGN_TASK_SELF', 'Assign Tasks to Self', 'Assign yourself to a task', 'TASK', true)
ON CONFLICT (key) DO NOTHING;
