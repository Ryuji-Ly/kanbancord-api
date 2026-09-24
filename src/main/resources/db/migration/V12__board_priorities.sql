-- Task priority was free text, so "High", "high" and "HIGH" were different priorities and nothing
-- ordered them. Each board now has its own ordered priority levels, and a task points at one.

CREATE TABLE board_priorities (
    priority_id BIGSERIAL PRIMARY KEY,
    board_id BIGINT NOT NULL REFERENCES boards(board_id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL,
    color VARCHAR(20),
    -- 1 is the most urgent; the list is shown in this order.
    position INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_board_priorities_board_name ON board_priorities(board_id, lower(name));
CREATE INDEX idx_board_priorities_board_id ON board_priorities(board_id);

-- Every existing board gets the defaults that new boards get.
INSERT INTO board_priorities (board_id, name, color, position)
SELECT b.board_id, d.name, d.color, d.position
FROM boards b
CROSS JOIN (VALUES
    ('Critical', '#dc2626', 1),
    ('High', '#ea580c', 2),
    ('Medium', '#ca8a04', 3),
    ('Low', '#2563eb', 4),
    ('Ignorable', '#6b7280', 5)
) AS d(name, color, position);

-- Priorities typed into tasks that match no default become levels of their own, after the defaults.
INSERT INTO board_priorities (board_id, name, color, position)
SELECT board_id, name, '#64748b', 5 + ROW_NUMBER() OVER (PARTITION BY board_id ORDER BY lower(name))
FROM (
    SELECT DISTINCT ON (t.board_id, lower(btrim(t.priority))) t.board_id, left(btrim(t.priority), 50) AS name
    FROM tasks t
    WHERE btrim(coalesce(t.priority, '')) <> ''
      AND NOT EXISTS (
          SELECT 1 FROM board_priorities p
          WHERE p.board_id = t.board_id AND lower(p.name) = lower(btrim(t.priority)))
    ORDER BY t.board_id, lower(btrim(t.priority)), t.priority
) custom;

ALTER TABLE tasks ADD COLUMN priority_id BIGINT REFERENCES board_priorities(priority_id) ON DELETE SET NULL;
CREATE INDEX idx_tasks_priority_id ON tasks(priority_id);

UPDATE tasks t
SET priority_id = p.priority_id
FROM board_priorities p
WHERE p.board_id = t.board_id AND lower(p.name) = lower(left(btrim(t.priority), 50));

ALTER TABLE tasks DROP COLUMN priority;

-- Managing a board's priority levels is its own permission. Everyone who could create labels gets it.
INSERT INTO kanban_permissions (key, name, description, category, is_system)
VALUES ('MANAGE_PRIORITIES', 'Manage Priorities', 'Create, edit, reorder and delete priority levels', 'LABEL', true)
ON CONFLICT (key) DO NOTHING;

INSERT INTO permissions (scope_type, scope_id, subject_type, subject_id, kanban_permission_id, state, priority,
                         is_immutable)
SELECT r.scope_type, r.scope_id, r.subject_type, r.subject_id,
       (SELECT permission_id FROM kanban_permissions WHERE key = 'MANAGE_PRIORITIES'),
       r.state, r.priority, r.is_immutable
FROM permissions r
JOIN kanban_permissions k ON k.permission_id = r.kanban_permission_id
WHERE k.key = 'CREATE_LABEL';
