ALTER TABLE reservations ADD COLUMN trip_id VARCHAR(100);
CREATE INDEX reservations_trip_idx ON reservations(trip_id);
CREATE INDEX calls_search_idx ON calls(search_id);
CREATE INDEX reservations_search_idx ON reservations(search_id);
