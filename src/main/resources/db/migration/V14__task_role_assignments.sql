-- Discord roles assigned to a task: anyone with the role can pick it up. Informational only; what a
-- member may do on the task is still decided by permissions. Goes when the task or the role does.
CREATE TABLE task_role_assignments (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES tasks(task_id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(role_id) ON DELETE CASCADE,
    assigned_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_task_role_assignments_task_role UNIQUE (task_id, role_id)
);

CREATE INDEX idx_task_role_assignments_task_id ON task_role_assignments(task_id);
