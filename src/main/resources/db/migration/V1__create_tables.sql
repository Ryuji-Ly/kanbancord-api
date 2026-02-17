-- Create USERS table
CREATE TABLE users (
    user_id BIGINT PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    global_name VARCHAR(255),
    avatar_url TEXT,
    preferences JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create SERVERS table
CREATE TABLE servers (
    server_id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    icon_url TEXT,
    owner_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_servers_owner FOREIGN KEY (owner_id) REFERENCES users(user_id)
);

-- Create SERVER_MEMBERS table
CREATE TABLE server_members (
    id BIGSERIAL PRIMARY KEY,
    server_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    nickname VARCHAR(255),
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_server_members_server FOREIGN KEY (server_id) REFERENCES servers(server_id) ON DELETE CASCADE,
    CONSTRAINT fk_server_members_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT uk_server_members_server_user UNIQUE (server_id, user_id)
);

-- Create ROLES table
CREATE TABLE roles (
    role_id BIGINT PRIMARY KEY,
    server_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    color INTEGER,
    position INTEGER,
    discord_permissions BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_roles_server FOREIGN KEY (server_id) REFERENCES servers(server_id) ON DELETE CASCADE
);

-- Create MEMBER_ROLES table
CREATE TABLE member_roles (
    id BIGSERIAL PRIMARY KEY,
    server_member_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_member_roles_server_member FOREIGN KEY (server_member_id) REFERENCES server_members(id) ON DELETE CASCADE,
    CONSTRAINT fk_member_roles_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE,
    CONSTRAINT uk_member_roles_member_role UNIQUE (server_member_id, role_id)
);

-- Create BOARDS table
CREATE TABLE boards (
    board_id BIGSERIAL PRIMARY KEY,
    server_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_boards_server FOREIGN KEY (server_id) REFERENCES servers(server_id) ON DELETE CASCADE,
    CONSTRAINT fk_boards_created_by FOREIGN KEY (created_by) REFERENCES users(user_id)
);

-- Create COLUMNS table
CREATE TABLE columns (
    column_id BIGSERIAL PRIMARY KEY,
    board_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    position NUMERIC(10, 2),
    color VARCHAR(50),
    wip_limit INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_columns_board FOREIGN KEY (board_id) REFERENCES boards(board_id) ON DELETE CASCADE
);

-- Create TASKS table
CREATE TABLE tasks (
    task_id BIGSERIAL PRIMARY KEY,
    board_id BIGINT NOT NULL,
    column_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    position NUMERIC(10, 2),
    priority VARCHAR(50),
    due_date TIMESTAMP,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    metadata JSONB,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_tasks_board FOREIGN KEY (board_id) REFERENCES boards(board_id) ON DELETE CASCADE,
    CONSTRAINT fk_tasks_column FOREIGN KEY (column_id) REFERENCES columns(column_id) ON DELETE CASCADE,
    CONSTRAINT fk_tasks_created_by FOREIGN KEY (created_by) REFERENCES users(user_id)
);

-- Create TASK_ASSIGNMENTS table
CREATE TABLE task_assignments (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    assigned_by BIGINT NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_assignments_task FOREIGN KEY (task_id) REFERENCES tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_assignments_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_assignments_assigned_by FOREIGN KEY (assigned_by) REFERENCES users(user_id),
    CONSTRAINT uk_task_assignments_task_user UNIQUE (task_id, user_id)
);

-- Create TASK_COMMENTS table
CREATE TABLE task_comments (
    comment_id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reply_to BIGINT,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT fk_task_comments_task FOREIGN KEY (task_id) REFERENCES tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_comments_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_comments_reply_to FOREIGN KEY (reply_to) REFERENCES task_comments(comment_id) ON DELETE CASCADE
);

-- Create LABELS table
CREATE TABLE labels (
    label_id BIGSERIAL PRIMARY KEY,
    board_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    color VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_labels_board FOREIGN KEY (board_id) REFERENCES boards(board_id) ON DELETE CASCADE
);

-- Create TASK_LABELS table
CREATE TABLE task_labels (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    label_id BIGINT NOT NULL,
    added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_labels_task FOREIGN KEY (task_id) REFERENCES tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_labels_label FOREIGN KEY (label_id) REFERENCES labels(label_id) ON DELETE CASCADE,
    CONSTRAINT uk_task_labels_task_label UNIQUE (task_id, label_id)
);

-- Create KANBAN_PERMISSIONS table
CREATE TABLE kanban_permissions (
    permission_id SERIAL PRIMARY KEY,
    key VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(100)
);

-- Create PERMISSIONS table
CREATE TABLE permissions (
    id BIGSERIAL PRIMARY KEY,
    scope_type VARCHAR(100) NOT NULL,
    scope_id BIGINT NOT NULL,
    subject_type VARCHAR(100) NOT NULL,
    subject_id BIGINT NOT NULL,
    kanban_permission_id INTEGER NOT NULL,
    state VARCHAR(50) NOT NULL,
    priority INTEGER NOT NULL,
    is_immutable BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_permissions_kanban_permission FOREIGN KEY (kanban_permission_id) REFERENCES kanban_permissions(permission_id) ON DELETE CASCADE
);

-- Create AUDIT_LOG table
CREATE TABLE audit_log (
    log_id BIGSERIAL PRIMARY KEY,
    server_id BIGINT,
    board_id BIGINT,
    user_id BIGINT,
    action VARCHAR(255) NOT NULL,
    entity_type VARCHAR(100),
    entity_id BIGINT,
    source VARCHAR(100),
    changes JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_log_server FOREIGN KEY (server_id) REFERENCES servers(server_id) ON DELETE CASCADE,
    CONSTRAINT fk_audit_log_board FOREIGN KEY (board_id) REFERENCES boards(board_id) ON DELETE CASCADE,
    CONSTRAINT fk_audit_log_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL
);

-- Create NOTIFICATIONS table
CREATE TABLE notifications (
    notification_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id BIGINT,
    message TEXT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- Create indexes for better query performance
CREATE INDEX idx_servers_owner_id ON servers(owner_id);
CREATE INDEX idx_server_members_server_id ON server_members(server_id);
CREATE INDEX idx_server_members_user_id ON server_members(user_id);
CREATE INDEX idx_roles_server_id ON roles(server_id);
CREATE INDEX idx_member_roles_server_member_id ON member_roles(server_member_id);
CREATE INDEX idx_member_roles_role_id ON member_roles(role_id);
CREATE INDEX idx_boards_server_id ON boards(server_id);
CREATE INDEX idx_boards_created_by ON boards(created_by);
CREATE INDEX idx_columns_board_id ON columns(board_id);
CREATE INDEX idx_tasks_board_id ON tasks(board_id);
CREATE INDEX idx_tasks_column_id ON tasks(column_id);
CREATE INDEX idx_tasks_created_by ON tasks(created_by);
CREATE INDEX idx_task_assignments_task_id ON task_assignments(task_id);
CREATE INDEX idx_task_assignments_user_id ON task_assignments(user_id);
CREATE INDEX idx_task_comments_task_id ON task_comments(task_id);
CREATE INDEX idx_task_comments_user_id ON task_comments(user_id);
CREATE INDEX idx_task_comments_reply_to ON task_comments(reply_to);
CREATE INDEX idx_labels_board_id ON labels(board_id);
CREATE INDEX idx_task_labels_task_id ON task_labels(task_id);
CREATE INDEX idx_task_labels_label_id ON task_labels(label_id);
CREATE INDEX idx_permissions_scope ON permissions(scope_type, scope_id);
CREATE INDEX idx_permissions_subject ON permissions(subject_type, subject_id);
CREATE INDEX idx_audit_log_server_id ON audit_log(server_id);
CREATE INDEX idx_audit_log_board_id ON audit_log(board_id);
CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
CREATE INDEX idx_notifications_user_id ON notifications(user_id);
CREATE INDEX idx_notifications_is_read ON notifications(user_id, is_read);
