CREATE TABLE IF NOT EXISTS txlog (
  id bigint PRIMARY KEY,
  facts jsonb,
  created_at timestamptz default now()
) WITH (OIDS=FALSE);

CREATE TABLE IF NOT EXISTS entity (
  id uuid PRIMARY KEY,
  attributes text[],
  created_at timestamptz default now(),
  modified_at timestamptz
) WITH (OIDS=FALSE);

CREATE TABLE IF NOT EXISTS properties (
  name text PRIMARY KEY;
  value text
) WITH (OIDS=FALSE);
