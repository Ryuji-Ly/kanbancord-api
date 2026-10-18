-- Change-only sync: the bot sends a fingerprint of a server's members and roles first, and the whole
-- list only when it differs from the one of the last full sync. Kept with when that sync happened, so
-- a full sync still runs at least once a day.
ALTER TABLE servers ADD COLUMN sync_fingerprint VARCHAR(64);
ALTER TABLE servers ADD COLUMN synced_at TIMESTAMPTZ;
