ALTER TABLE announcements
  ADD COLUMN total_trips_counted BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN announcements.total_trips_counted IS
  'Idempotence flag: TRUE once users.total_trips has been incremented for this announcement (one physical trip = one increment, regardless of how many bids are completed on it). Set when the FIRST bid of the announcement reaches COMPLETED.';
