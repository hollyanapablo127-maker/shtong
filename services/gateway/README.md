# Gateway modular monolith

This Spring Boot process implements the phase-one account, authorization, organization,
module-catalog, audit, and admin boundaries. The `development` profile uses process-local
state for UI and contract development. The `production` profile requires PostgreSQL,
runs Flyway with the migration principal, encrypts the authoritative checkpoint, and
persists the append-only audit ledger in the same database transaction.

Production must use separate principals: `SHTONG_MIGRATION_DATABASE_USER` owns migration
work, while `SHTONG_DATABASE_USER` inherits only `shtong_runtime`. The committed Compose
stack provisions this split for a new local database. Existing databases require the same
grant to be managed by the deployment platform.

No default account or password is stored in source or images. Initial bootstrap only runs
when all four administrator values are supplied together. Production additionally requires
a 32-character `SHTONG_BOOTSTRAP_SECRET`; remove the secret and four administrator values
from the deployment immediately after the one-time two-administrator bootstrap succeeds.

Run locally with JDK 21 and the repository Maven Wrapper:

```powershell
.\mvnw.cmd -f services/gateway/pom.xml spring-boot:run
```

For a production-profile Compose rehearsal, create a private `.env` from `.env.example`,
set every required database, encryption, refresh-token, and MinIO secret, then use
`docker compose --env-file .env -f infra/docker/compose.yml up --build`.
