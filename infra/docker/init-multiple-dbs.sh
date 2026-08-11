#!/usr/bin/env bash
# Creates one database + owning user per microservice, driven by the
# POSTGRES_MULTIPLE_DATABASES env var (format: db:user:password,db:user:password,...).
# This is the standard community pattern for bootstrapping multiple per-service databases
# in a single Postgres container for local/dev use — each service still connects only to
# its own database, preserving the "database per service" boundary even though they share
# one physical instance here for simplicity. In staging/production each service would get
# its own managed Postgres instance instead.
set -euo pipefail

create_database_and_user() {
	local db=$1
	local user=$2
	local password=$3

	echo "Creating database '$db' owned by '$user'"
	psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
		DO \$\$
		BEGIN
			IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '$user') THEN
				CREATE ROLE $user LOGIN PASSWORD '$password';
			END IF;
		END
		\$\$;
		SELECT 'CREATE DATABASE $db OWNER $user' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
		GRANT ALL PRIVILEGES ON DATABASE $db TO $user;
	EOSQL

	psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-EOSQL
		CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid() used throughout the Flyway migrations
	EOSQL
}

if [ -n "${POSTGRES_MULTIPLE_DATABASES:-}" ]; then
	echo "Multiple database creation requested: $POSTGRES_MULTIPLE_DATABASES"
	IFS=',' read -ra ENTRIES <<< "$(echo "$POSTGRES_MULTIPLE_DATABASES" | tr -d '[:space:]')"
	for entry in "${ENTRIES[@]}"; do
		IFS=':' read -r db user password <<< "$entry"
		create_database_and_user "$db" "$user" "$password"
	done
	echo "Multiple databases created"
fi
