#!/bin/sh
set -eu

: "${SHTONG_DATABASE_USER:?SHTONG_DATABASE_USER is required}"
: "${SHTONG_DATABASE_PASSWORD:?SHTONG_DATABASE_PASSWORD is required}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=runtime_user="$SHTONG_DATABASE_USER" \
  --set=runtime_password="$SHTONG_DATABASE_PASSWORD" <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shtong_runtime') THEN
    CREATE ROLE shtong_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END $$;

SELECT format(
  'CREATE ROLE %I LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',
  :'runtime_user',
  :'runtime_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_user')
\gexec

SELECT format('GRANT shtong_runtime TO %I', :'runtime_user')
\gexec
SQL
