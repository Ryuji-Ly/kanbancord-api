-- Each person's time zone, so times they type in Discord mean their own clock. The website sets it from the
-- device; someone who chose one with /notifications keeps it, and the website then leaves it alone.
ALTER TABLE users ADD COLUMN time_zone VARCHAR(64);
ALTER TABLE users ADD COLUMN time_zone_chosen BOOLEAN NOT NULL DEFAULT FALSE;
