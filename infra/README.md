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
| Kafka | `localhost:9092` | `kafka:19092` | KRaft, single broker. Topics are **not** auto-created; the one-shot `kafka-init` service provisions them (see below). |
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

## Kafka topics

`infra/kafka/provision-topics.sh` is the only thing that creates topics (broker auto-create is
off). The one-shot `kafka-init` compose service runs it once Kafka is healthy, then exits. It
is **create-if-absent, verify-never-alter**: a missing topic is created with the exact spec §4.1
config (`pr.review.requested` 6 partitions / 7d, `pr.review.retry` 3 / 3d, `pr.review.dlq` 3 / 30d,
all `cleanup.policy=delete`); an existing topic is only compared. If it differs — most importantly
in partition count — the script exits non-zero and changes nothing. Never `--alter --partitions`
a keyed topic: `hash(key) % N` reshuffles and silently breaks per-PR ordering. Changing a count is
the topic-swap runbook in spec §4.4.

```bash
KAFKA_ENV=prod bash infra/kafka/provision-topics.sh --render   # prod values (RF=3 / ISR=2), no broker needed
docker compose -f infra/docker-compose.yml run --rm kafka-init # re-run by hand (safe to repeat)
bash infra/kafka/verify-topics.sh                              # run twice + --describe assertions + drift refusal
```

`up -d --wait` does **not** wait for one-shot services (`kafka-init`, `flyway`) to finish — a
container with no healthcheck counts as ready once started — so topics may appear a few seconds
after `up` returns; `verify-stack.sh` retries the topic check for up to 30 s. Verification is
strict: `cleanup.policy`, `retention.ms` and `min.insync.replicas` must be set at topic level,
and a value merely inherited from broker defaults is reported as drift.

The topic names and partition counts also exist as `shared` constants (E1-5); the script is
authoritative for infra — keep them in sync. CI job `kafka-topics` runs `verify-topics.sh` on a
fresh broker. How prod (MSK) runs the script is E11's call.

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

- k8s manifests — E11.
