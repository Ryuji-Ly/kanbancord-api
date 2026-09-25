-- Optional features a server has switched on. A server with none is in simple mode: boards,
-- columns, and tasks with a title and description. Switching a feature off hides it and refuses
-- changes to it; its data is kept.
CREATE TABLE server_features (
    server_id BIGINT NOT NULL REFERENCES servers(server_id) ON DELETE CASCADE,
    feature VARCHAR(40) NOT NULL,
    PRIMARY KEY (server_id, feature)
);

-- Servers that exist already keep everything they have been using. New servers start simple.
INSERT INTO server_features (server_id, feature)
SELECT s.server_id, f.feature
FROM servers s
CROSS JOIN (VALUES ('LABELS'), ('PRIORITIES'), ('ASSIGNEES'), ('COMMENTS'), ('DUE_DATES'), ('PERMISSIONS')) AS f(feature);
