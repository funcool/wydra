CREATE TABLE %s (
 eid uuid PRIMARY KEY,
 txid bigint,
 created_at timestamptz default now(),
 modified_at timestamptz,
 content %s
 %s
) WITH (OIDS=FALSE);
