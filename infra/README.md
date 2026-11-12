# infra — local dev stack

Docker Compose stack for running reviewbot by hand: Kafka (KRaft, single broker),
Postgres 16, Redis 7, and WireMock standing in for GitHub and Anthropic.
Integration tests do **not** use this — they use Testcontainers (spec §18).

## Up / down

Run from the repo root (or anywhere, with `-f`):

```bash
docker compose -f infra/docker-compose.yml up -d --wait   # start; --wait blocks until all healthy
infra/verify-stack.sh                                      # smoke test; exit 0 = all services answer
docker compose -f infra/docker-compose.yml down            # stop, keep data
docker compose -f infra/docker-compose.yml down -v         # stop and wipe Postgres + Kafka volumes
```

`verify-stack.sh` needs `bash` and `curl` on the host (Git Bash on Windows works).
It exits non-zero if any service is unreachable, including when the stack is down.

## Services

| Service | Host address | Container-network address | Notes |
|---|---|---|---|
| Kafka | `localhost:9092` | `kafka:19092` | KRaft, single broker. Topics are **not** auto-created (E2-4 provisions them). |
| Postgres 16 | `localhost:5432` | `postgres:5432` | db / user / password: `reviewbot` (local only). |
| Redis 7 | `localhost:6379` | `redis:6379` | No auth, no persistence. |
| WireMock | `http://localhost:8089` | `wiremock:8080` | Empty `infra/wiremock/mappings/`. Host port 8089 so 8080 stays free for the Spring Boot apps. |

Named volumes: `kafka-data`, `postgres-data`.

## Database migrations (Flyway)

Migrations live in `infra/db/migration/` (`V<n>__name.sql`). The one-shot `flyway` compose
service applies pending ones to the local Postgres after it is healthy, then exits; re-running
is a no-op (applied versions are recorded in `flyway_schema_history`). Never edit an applied
migration — Flyway's checksum check will refuse to run; add a new `V<n+1>__…` file instead.
Apps do **not** self-migrate (E2-3 / O1); in k8s a migration Job applies them (E11). The
schema smoke test (`shared`, `SchemaMigrationTest`) runs the same files against Testcontainers.

## Local Kafka diverges from production (intentional)

Local compose runs **RF=1 / `min.insync.replicas=1`**. Production (MSK / k8s) runs
**RF=3 / `min.insync.replicas=2`** (spec §4.4, §19.15). A single-broker KRaft
cluster cannot host RF=3, so this is documented, not fixed. Do not raise the
replication settings in the compose file, and do not chase `NOT_ENOUGH_REPLICAS`
errors locally — if you see one, you are not talking to this stack.

## Receiving real GitHub webhooks locally

GitHub must reach the webhook-gateway running on your machine. Use a tunnel:
[smee.io](https://smee.io) (`smee -u <channel> -t http://localhost:8080/...`) or
`ngrok http 8080`, and point the GitHub App's webhook URL at it (spec §19.15).

## Not here

- Topic creation — E2-4.
- k8s manifests — E11.
