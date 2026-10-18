-- A moved task is placed halfway between its new neighbours, so a move writes one row instead of
-- renumbering whole columns. Ten decimal places leave room for about 30 moves into the same gap
-- before the column has to be renumbered.
ALTER TABLE tasks ALTER COLUMN position TYPE NUMERIC(20, 10);
