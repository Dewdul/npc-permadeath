CREATE TABLE IF NOT EXISTS spawns (
  name TEXT NOT NULL,
  npc_id INTEGER NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  plane INTEGER NOT NULL,
  reports INTEGER NOT NULL DEFAULT 1,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  PRIMARY KEY (name, x, y, plane)
);
CREATE INDEX IF NOT EXISTS spawns_name ON spawns (name);
