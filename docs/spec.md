# reviewbot — System Design & Build Specification

**Version:** 1.2 · **Status:** Design reviewed (5-pass adversarial) + ticket-generation rulings (O1–O14) incorporated — implementation-ready
**Stack:** Java 21 · Spring Boot 3.x · PostgreSQL 16 · Redis 7 · Apache Kafka · Anthropic API · AWS (EKS, RDS, ElastiCache, MSK)

---

## 0. How to use this document

This is a **build document**, not a summary. Every component is specified to the contract level; every workflow is a numbered sequence with failure branches inline; every configuration value has its number and its reason. It is written to serve as implementation context for Claude Code or for step-by-step manual implementation — no design decisions are left open.

Conventions:
- **[v1] / [v1.5] / [v2]** tags mark which build layer a mechanism belongs to. Build v1 completely before starting v1.5. Anything untagged is v1.
- **⚠ VERIFY** marks the small number of external-API details to confirm against current GitHub/Anthropic docs during implementation (APIs change; the design does not depend on them being exactly as stated, and fallbacks are specified).
- **INVARIANT** marks rules that must never be violated; each should have a dedicated test.
- §20 converts this document into epics and tickets. §Appendix records alternatives considered and rejected, with reasons (interview preparation material).

Reading order for implementation: §2 → §3 → §5 → §4 → §6 → §7 → §8 → §9 → §10 → §11, with §12 open beside you throughout.

---

## 1. System overview

**Product:** A GitHub App that posts AI-generated code reviews on pull requests. Installed per-repo via GitHub Marketplace; reviews the current state of a PR on open/push; supports incremental re-review (only what changed since the last review), severity filtering, and BYOK (bring-your-own Anthropic API key).

**Architecture in one paragraph:** GitHub webhooks hit the **webhook-gateway**, which verifies HMAC, deduplicates deliveries via Redis, and produces `ReviewRequested` events to Kafka. The **review-worker** consumes with a hand-built key-ordered parallel consumer (virtual threads, per-PR serialization, watermark commits), runs each review through an idempotent pipeline (dedup → rate-limit → live-fetch → lock → LLM → post → finalize), and posts the review back to GitHub. A **retry-worker** re-attempts transient failures and deferrals after delays; exhausted or terminal failures land in a **DLQ** with a diagnostic envelope, browsable and replayable via admin endpoints. PostgreSQL is the durable source of truth; Redis is the fast path (dedup cache, distributed lock); Kafka is the durable buffer that makes every outage survivable.

**Correctness posture:** at-least-once delivery everywhere, made safe by idempotent effects. Per-PR ordering via partition keying + in-worker per-key serialization + distributed lock. External side effects (GitHub posts) protected in v1 by lock-with-watchdog + dedup markers + a post-attempt guard; hardened in v2 by fence tokens + reconciliation + marker-based find-or-create.

**Scale posture:** *designed* for 1,000 events/sec (async I/O-bound concurrency, documented scaling path per component, §16); *deployed* for ~10 events/sec (6 partitions, 2 worker pods, single-node Redis/Postgres). The binding ceiling at high scale is external API rate limits (GitHub, Anthropic), not infrastructure — the rate-limit/defer/breaker machinery is the primary control system, by design.

**Non-goals (v1–v2):** billing (Marketplace/Stripe — v3), inline line-level comments (v2+), multi-provider LLM adapters beyond the port (seam built, one adapter shipped), conversational replies to the bot (v3), exactly-once semantics against GitHub (provably unattainable — see §13.4).

---

## 2. Module & deployment architecture

**Single repository, multi-module** (Gradle or Maven multi-module). Modules build independent container images; shared types live in one place; a schema change is one atomic commit.

```
reviewbot/
├── shared/            # event envelope, DLQ envelope, PR key, Redis key builders,
│                      #   ReviewGenerator port, failure classification, config models
├── webhook-gateway/   # deployable: HMAC verify, delivery dedup, produce to Kafka
├── review-worker/     # deployable: parallel consumer engine + core review pipeline
│                      #   + DLQ indexer thread + admin endpoints [v1.5 hardening: own module]
├── retry-worker/      # deployable: delay-aware retry consumer; calls the same core
└── infra/             # docker-compose (local), k8s manifests, Flyway migrations, dashboards
```

Deployables and scaling shape:

| Deployable | Instances (deploy) | Scales on | Notes |
|---|---|---|---|
| webhook-gateway | 2 (HA) | CPU / request rate | Must respond to GitHub < 10 s (target < 1 s). Also hosts `/settings/**` (O13); only DB access is an `installations`-repository slice — no code path to `reviews`/`dlq_entries` |
| review-worker | 2 | consumer lag / in-flight saturation | Max useful = partition count (6) |
| retry-worker | 1 | retry-topic lag | Sequential per partition is sufficient at deploy scale |
| Postgres (RDS) | 1 (multi-AZ) | §16 | Source of truth |
| Redis (ElastiCache) | 1 (multi-AZ) | §16 | Fast path only; system correct (slower/paused) without it |
| Kafka (MSK) | 3 brokers | §16 | replication 3 everywhere |

**Dependency rule:** `webhook-gateway`, `review-worker`, `retry-worker` depend on `shared`; never on each other. The core pipeline function `processReview(event, attemptContext)` lives in `shared` (or a `core` module) and is called identically by review-worker and retry-worker — one implementation, both paths. This seam is load-bearing: it is what makes the retry path exactly-as-correct as the main path.

**Admin/DLQ endpoints** live inside review-worker in v1 (`/admin/**`, internal-only: no public ingress, protected by network policy + basic auth). Promote to a separate `admin` module only if resource isolation is ever needed.

---

## 3. Event schema & evolution policy

**Format: JSON.** Rationale (decision log #3): producer and consumer share the same class from `shared` in the same commit — the monorepo *is* the contract enforcement a schema registry would provide across teams; the DLQ envelope must be human-readable (its purpose is human diagnosis); payload size (1–2 KB) is irrelevant next to LLM cost per event at any scale we target. **Migration trigger to Avro + AWS Glue Schema Registry:** a second team or external system consumes these topics, or payload bandwidth becomes a measured cost.

### 3.1 ReviewRequested envelope (topic value)

```json
{
  "schema_version": 1,
  "event_id": "c1a2...-uuid",
  "delivery_id": "value of X-GitHub-Delivery header",
  "type": "REVIEW_REQUESTED",
  "occurred_at": "2026-07-05T10:00:00Z",
  "installation_id": 12345,
  "repo_full_name": "acme/app",
  "pr_number": 17,
  "head_sha": "abc123...",
  "force": false,
  "replay": false
}
```

- `type` enum: `REVIEW_REQUESTED` [v1]; `REREVIEW_COMMAND`, `MODEL_COMMAND` [v1.5].
- `force`: bypasses dedup (set by `/rereview` [v1.5]); default false. `replay`: set true by DLQ replay (§10.3) — enables the FAILED→PROCESSING claim path (§7.6).
- `event_id`, `occurred_at`: tracing/observability only, not consumed by any mechanism. `occurred_at` source = PR `updated_at`.
- **Kafka message key** (all three topics): the string `"{installation_id}:{repo_full_name}:{pr_number}"` — same PR → same partition → ordered.

### 3.2 Kafka headers (retry/DLQ metadata — never in the payload)

| Header | Set on | Meaning |
|---|---|---|
| `not_before` (epoch ms) | retry topic | earliest time to re-attempt |
| `error_attempts`, `defer_attempts` | retry topic | mirrors of DB counters (DB is source of truth on conflict) |
| `failure_class` | retry + DLQ | enum, §10.1 |
| `first_received_at`, `first_deferred_at` (epoch-ms strings) | all / retry | timestamps; SLA measured from `first_deferred_at` (§7.2) |
| `trace_id` | all | OpenTelemetry correlation |

Header **codecs live in the `shared` module** (both workers use them — a codec mismatch is a silent interop bug). Encodings: `not_before`, `first_received_at`, `first_deferred_at` = epoch-ms strings; `error_attempts`, `defer_attempts` = decimal-int strings.

### 3.3 Evolution rules (the registry replacement — enforce in code review)

1. **Additive only.** New fields are optional with defaults. Never rename, remove, retype, or repurpose a field.
2. **Tolerant reader.** Jackson: `FAIL_ON_UNKNOWN_PROPERTIES = false` on all consumers. Old consumers must survive new producers.
3. **`schema_version` bump** only for a breaking change — which per rule 1 should never happen; if it must, it is a new topic (`.v2`) and a dual-write migration (§4.4), not an in-place change.
4. Required-field validation on consume (O2): all §3.1 fields required except `force`/`replay` (defaulted); `schema_version` must equal 1; `head_sha` required for `REVIEW_REQUESTED` (optional for command types, P4-3). Validation failure = `POISON_DESERIALIZATION` → DLQ (§10.1).

---

## 4. Kafka design

### 4.1 Topics

| Topic | Partitions | Replication | Retention | Cleanup | Key |
|---|---|---|---|---|---|
| `pr.review.requested` | 6 | 3 | 7 days | delete | `inst:repo:pr` |
| `pr.review.retry` | 3 | 3 | 3 days | delete | `inst:repo:pr` |
| `pr.review.dlq` | 3 | 3 | 30 days | delete | `inst:repo:pr` |

- **6 partitions** = consumer-parallelism ceiling + failover headroom at deploy scale, cleanly divisible by 1/2/3/6 consumers. Throughput is NOT the constraint (async concurrency inside each consumer is the throughput lever, §8); partitions size the *instance* count. Scale trigger and migration: §4.4, §16.
- **Compaction is wrong for all three** — these are event streams (every push matters until superseded), not state topics. Time-based retention only.
- Retry retention (3d) comfortably exceeds max cumulative backoff (~20 min error schedule; 2 h defer SLA). DLQ 30d = human action window; an un-drained DLQ self-cleans rather than growing forever (never the plan, always the backstop).

### 4.2 Producer configuration (gateway; workers when producing to retry/DLQ)

| Setting | Value | Why |
|---|---|---|
| `acks` | `all` | Leader + ISR must ack; survives any single broker loss |
| `min.insync.replicas` (topic) | 2 | Gives `acks=all` teeth: with RF=3, tolerate 1 broker down; refuse under-replicated writes (durability over availability — correct for this system) |
| `enable.idempotence` | `true` | Broker dedupes producer-side retry duplicates (sequence numbers). Free correctness; note: covers *producer retries only*, not semantic duplicates — those are the dedup layer's job |
| `max.in.flight.requests.per.connection` | 5 | Max value at which idempotent producer still guarantees ordering under retry |
| `retries` / `delivery.timeout.ms` | default / 120s | Retry hard within the timeout; failure surfaces to the caller (gateway → 503, worker → outcome handling) |
| `compression.type` | `lz4` | Free bandwidth win; negligible CPU |

### 4.3 Consumer configuration (both workers)

| Setting | Value | Why |
|---|---|---|
| `group.id` | `review-worker` / `retry-worker` | Independent groups |
| `enable.auto.commit` | `false` | Watermark-driven manual commits only (§8.4) |
| `partition.assignment.strategy` | `CooperativeStickyAssignor` | Incremental rebalances; only moving partitions pause |
| `session.timeout.ms` / `heartbeat.interval.ms` | 45000 / 3000 | Crash detection; poller always heartbeats |
| `max.poll.interval.ms` | 300000 (default) | Poller cycles in ms — never approached (§8.1). Do not raise; a violation now means a real bug |
| `max.poll.records` | 100 | Must be ≤ in-flight cap (200) so a full batch always fits available permits and the intake loop never blocks mid-drain (P1-3/CF-1); true bound is the backpressure semaphore |
| `auto.offset.reset` | `earliest` | New group / lost offsets → process backlog, never skip |

### 4.4 Partition-count migration runbook (documented, not scheduled)

`--alter --partitions` is **forbidden** on keyed topics: `hash(key) % N` reshuffles, old and new events for one PR land on different partitions, ordering breaks silently. The only correct procedure is a topic swap: (1) create `pr.review.requested.v2` with the new count; (2) gateway dual-writes to both; (3) verify v2 receives everything; (4) stop producing to v1 → drain v1 consumers to lag 0 → stop them; (5) start v2 consumers from the dual-write start offset; (6) delete v1 after retention. Ordering is preserved by the drain-before-cutover step; idempotent consumers make the dual-write window safe. Trigger: §16.

**Local dev override:** single-broker KRaft cannot host RF=3 / min.insync.replicas=2 — local compose uses RF=1, min.insync.replicas=1; production (MSK) uses RF=3 / ISR=2. Don't chase phantom `NOT_ENOUGH_REPLICAS` errors locally.

---

## 5. Data stores

### 5.1 PostgreSQL schema (Flyway migrations in `infra/`)

```sql
CREATE TABLE reviews (
    id                 BIGSERIAL PRIMARY KEY,
    installation_id    BIGINT       NOT NULL,
    repo_full_name     TEXT         NOT NULL,
    pr_number          INT          NOT NULL,
    head_sha           TEXT         NOT NULL,
    round              INT          NOT NULL DEFAULT 1,   -- re-review discriminator (C-4)
    status             TEXT         NOT NULL,  -- PROCESSING | POSTING(v2) | AWAITING_RETRY
                                               -- | SUCCEEDED | FAILED | SKIPPED
    fence              BIGINT       NOT NULL DEFAULT 0,   -- [v2] monotonic claim token
    error_attempts     INT          NOT NULL DEFAULT 0,
    defer_attempts     INT          NOT NULL DEFAULT 0,
    failure_class      TEXT,                                -- terminal failure class, OR skip reason on
                                                            -- SKIPPED rows (PR_CLOSED, SUSPENDED,
                                                            -- AUTHOR_NOT_PERMITTED, ...)
    github_review_id   BIGINT,                              -- returned by GitHub on post
    post_attempted_at  TIMESTAMPTZ,                         -- set immediately before POST (§13.2 guard)
    first_received_at  TIMESTAMPTZ  NOT NULL,
    first_deferred_at  TIMESTAMPTZ,                          -- set on first DEFER; SLA clock (C-3)
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    UNIQUE (installation_id, repo_full_name, pr_number, head_sha, round)
);
CREATE INDEX idx_reviews_rate ON reviews (installation_id, status);           -- rate-limit count
CREATE INDEX idx_reviews_last ON reviews (installation_id, repo_full_name,
                                          pr_number, completed_at DESC)
    WHERE status = 'SUCCEEDED';                                               -- incremental base lookup

CREATE TABLE installations (
    installation_id    BIGINT PRIMARY KEY,
    api_key_encrypted  BYTEA,           -- AES-GCM via KMS data key; NEVER plaintext, NEVER logged
    default_model      TEXT NOT NULL DEFAULT 'claude-sonnet-4-6',
    suspended          BOOLEAN NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dlq_entries (                                   -- DLQ indexer target (§10.3)
    id               BIGSERIAL PRIMARY KEY,
    envelope         JSONB NOT NULL,                         -- full §10.2 envelope
    failure_class    TEXT  NOT NULL,
    repo_full_name   TEXT,
    pr_number        INT,
    head_sha         TEXT,                                   -- nullable (POISON has no identity)
    installation_id  BIGINT,                                 -- nullable for POISON (D-2)
    dlq_partition    INT NOT NULL,
    dlq_offset       BIGINT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'NEW',            -- NEW | REPLAYED | DISCARDED
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dlq_partition, dlq_offset)                       -- indexer crash-safe (D-5)
);
CREATE INDEX idx_dlq_status ON dlq_entries (status, failure_class);
CREATE INDEX idx_dlq_tenant ON dlq_entries (installation_id, failure_class);  -- flood triage (P2-5)
```

**Status semantics** (state machine): `PROCESSING` = claimed, work underway (counts toward rate limit). `POSTING` [v2] = fenced claim to post (counts toward rate limit). `AWAITING_RETRY` = deferred or transient-failed, parked on retry topic (does NOT count toward rate limit). `SUCCEEDED` / `FAILED` / `SKIPPED` = terminal. Allowed transitions:
- `∅ → PROCESSING | SKIPPED | FAILED` (a fresh event may skip or fail before any claim)
- `PROCESSING → POSTING(v2) | SUCCEEDED | FAILED | AWAITING_RETRY | SKIPPED`
- `AWAITING_RETRY → PROCESSING | SKIPPED | FAILED`
- `POSTING → SUCCEEDED | FAILED | AWAITING_RETRY`
- `FAILED → PROCESSING` **only when the claim context is `replay=true`** (§10.3 DLQ replay); resets `error_attempts=1, defer_attempts=0`, restamps `first_received_at`, clears `first_deferred_at`.
- Terminal (`SUCCEEDED`/`FAILED`/`SKIPPED`) otherwise final. Anything else is a bug.

### 5.2 Redis key map

| Key | Type / value | TTL | Written by | Purpose |
|---|---|---|---|---|
| `delivery:{X-GitHub-Delivery}` | `SET NX` flag | 24 h | gateway | webhook delivery dedup (GitHub retry window) |
| `dedup:{inst}:{repo}:{pr}:{sha}:{round}` | flag (existence = reviewed-and-posted) | 7 d | worker finalize | semantic dedup, per-(SHA,round). **Means reviewed-and-posted ONLY** — never "skipped" or "superseded" (P1-1). Normal reviews use round 1; `/rereview` uses the next round. |
| `lock:review:{inst}:{repo}:{pr}` | Redisson RLock | 30 s, watchdog-renewed ~10 s | worker | cross-instance mutual exclusion per PR (no SHA — one SHA of a PR in flight at a time is the point) |
| `ghcfg:{inst}:{repo}` | JSON of `.reviewbot.yml` | 5 min | worker | repo config cache |

Skip/supersede states are **never** written to `dedup:` — conflating "skipped" with "reviewed" silently suppresses a later revert to that SHA (P1-1). If skip-diagnostics are ever needed, use a separate `skip:` namespace that §7 step 1 never reads.

Redis is **never** a source of truth: dedup falls back to the DB on miss and repopulates (§7 step 1); the lock is deliberately ephemeral (a "durable lock" is a contradiction — its value is its liveness); rate limiting is DB-derived in v1 (decision log #4). Consequence: Redis loss degrades speed or pauses processing (§11), never correctness.

---

## 6. Webhook gateway

Stateless Spring Boot service. Hard requirement: respond to GitHub in well under its 10 s webhook timeout — target < 1 s. The gateway does the minimum to make the event durable, then gets out of the way.

### 6.1 Endpoint: `POST /webhook`

1. **Verify HMAC** (`X-Hub-Signature-256`, constant-time comparison against the app's webhook secret). Fail → `401`, stop. Never process an unverified payload.
2. **Filter event types.** Accept: `pull_request` with action ∈ {`opened`, `synchronize`, `reopened`, `ready_for_review`}; [v1.5] `issue_comment` / `pull_request_review_comment` with action `created` **and `issue.pull_request` present** (P4-3). Everything else → `200` (ack, no-op) — GitHub must not retry events we deliberately ignore. **Draft PRs:** if `pull_request.draft` is true, skip by default — `review_drafts` is **gateway app-level config, global** (O4: the gateway holds no GitHub token and cannot read `.reviewbot.yml`, so per-repo opt-in is structurally unavailable here; deferred); `ready_for_review` is treated as `opened` (P4-2).
3. **Build envelope** (§3.1): `event_id = UUID`, `head_sha = payload.pull_request.head.sha` (optional for command types — worker resolves via live fetch, P4-3), `occurred_at` = PR `updated_at`, `first_received_at = now` header.
4. **Dedup-check, produce, THEN mark** (order fixed — C-1):
   a. `EXISTS delivery:{X-GitHub-Delivery}` → present → `200`, stop (already durably enqueued). **Redis unreachable on this read → fail open, produce anyway** — downstream semantic dedup absorbs a duplicate; rejecting a webhook because a *cache* is down would risk real event loss.
   b. **Produce** to `pr.review.requested` (config §4.2), key `inst:repo:pr`. Await ack, **bounded at 5 s** (P4-1 — well under GitHub's 10 s webhook timeout; prevents thread exhaustion under Kafka-slow).
   c. Ack **success** → `SET delivery:{guid} NX EX 86400` (best-effort; a failed set only risks a downstream-absorbed duplicate) → `200`. The marker now means **"durably enqueued,"** not "seen."
   d. Ack **failure/timeout** → **`503`**, and **do NOT set the delivery marker** — GitHub redelivers and the event still has a path (C-1: setting the marker before the ack silently loses events). GitHub's retry system is the upstream buffer; undelivered events remain manually redeliverable via GitHub's API. **No DB-buffer fallback** (decision log #7).

No per-event DB access → no transactional outbox needed in v1 (decision log #8). Post-Kafka-outage runbook: optional redelivery sweep via GitHub's deliveries API. ⚠ VERIFY exact redelivery API path and retention window when implementing the sweep.

### 6.2 GitHub App authentication (O6)

All GitHub API calls (workers and gateway alike) authenticate as the GitHub App: sign a short-lived **JWT** with the app id + private key (PEM via secret config — BYOK-grade secrecy, §19 trap), exchange it per installation for an **installation token** (~1 h lifetime — ⚠ VERIFY), cached per installation and refreshed **~5 min before expiry**. On a 401 using a cached token: force one refresh, retry once. Classification: a 401 on the *token exchange itself* after retry = credential/config failure on **our** side → dedicated alert, never per-message counters; a 401/403 *using* a token that a forced refresh does not fix = `INSTALLATION_REVOKED` (existing terminal class).

---

## 7. Review pipeline — the core flow

`processReview(event, ctx)` lives in `shared/core` and is called by **both** workers. Written as plain sequential blocking code — it always executes inside a drainer (virtual thread) that guarantees per-PR serialization within the instance; the Redisson lock guarantees it across instances.

Every step's failure routes to exactly one **outcome** (§7.2). INVARIANT (I5): every record reaches exactly one terminal outcome — `SUCCESS | SKIP | DEFER | TRANSIENT | TERMINAL | BREAKER_PARKED` — before `markComplete`. No silent drops: the drainer's outermost catch-all classifies and routes; nothing falls through.

### 7.1 Steps (v1; [v2] insertions marked)

**Step 0 — coalescing (drainer, before this function).** Drain the PR queue; partition by `type`. `REVIEW_REQUESTED` events collapse to the newest (highest offset — offset order *is* arrival order); superseded ones are immediately terminal (`SKIP/SUPERSEDED`, `markComplete`, no DB row). [v1.5] Command events survive coalescing and run in queue order. Cost of superseded backlog: one batch bookkeeping op, microseconds.

**1. Dedup — event SHA.** `EXISTS dedup:{i}:{r}:{p}:{event.head_sha}` → hit → `SKIP`. Redis miss (or Redis down) → DB: `SELECT 1 FROM reviews WHERE (i,r,p,sha) AND status='SUCCEEDED'` → hit → repopulate Redis (best-effort) → `SKIP`. Miss both → continue. *(Cheapest check first; an already-done event must not consume a rate-limit slot or a GitHub call.)*

**2. Rate limit.** `SELECT count(*) FROM reviews WHERE installation_id=? AND status IN ('PROCESSING','POSTING')` (index `idx_reviews_rate`; approximate limiting is acceptable — this is backpressure, not correctness). count ≥ cap (**5**, config) → `DEFER(RATE_LIMIT)`.

**2.5 Author authorization (S-1).** Determine the PR author's permission (collaborators API, github breaker; cached **in-memory per worker**, 5 min TTL, bounded — O7: not Redis, since per-PR serialization pins a PR's events to one worker and §5.2 stays the honest list of shared state; staleness bound: a just-revoked author is reviewable ≤5 min). Policy from `.reviewbot.yml` `review_authors` (default `write`): `write` → author must have write access; `contributors` → write OR a prior merged contribution; `all` → anyone (opt-in — owner accepts the billing exposure). Ineligible → `SKIPPED(AUTHOR_NOT_PERMITTED)` (+ [v1.5] optional advisory). Runs **before** the diff fetch so an unauthorized author costs no LLM and no diff GET.

**2.6 Key presence (V-1).** No decryptable BYOK key for this installation → `TERMINAL(MISSING_OR_INVALID_API_KEY)` (+ [v1.5] advisory "configure your key at <link>"). Checked before the GitHub fetch so keyless installs don't burn work or page on every PR.

**3. Live fetch** — `GET /repos/{r}/pulls/{p}` (github breaker, timeouts §12).
   - PR `state` closed/merged → upsert row `SKIPPED(PR_CLOSED)` → `SKIP`. **Do NOT write a dedup marker** (a reopened PR must review; markers mean reviewed-only — P1-1).
   - `404` → `TERMINAL(PR_NOT_FOUND)`. Permission-class `401/403` (no rate-limit headers) → `TERMINAL(INSTALLATION_REVOKED)`.
   - `429` / rate-limit headers → `DEFER(PROVIDER_THROTTLE)`, `not_before` from `Retry-After`/reset header.
   - `5xx`/timeout → `TRANSIENT`. Breaker open → `BREAKER_PARKED`. **Postgres/Redis op failure anywhere in the pipeline → `BREAKER_PARKED(dep)`** (no counters, re-park at health-recheck cooldown — P1-4).
   - Else: `current = pr.head.sha`. If `current ≠ event.head_sha`: proceed with `sha := current`. **Do NOT write a dedup marker for the superseded event SHA** (P1-1 — conflating superseded with reviewed suppresses a later revert to that SHA).

**4. Dedup — current SHA.** As step 1 → hit → `SKIP`.

**5. Lock.** `RLock.tryLock(waitTime=2s)` on `lock:review:{i}:{r}:{p}`, acquired **without leaseTime** (Redisson watchdog: 30 s TTL auto-renewed ~10 s from Redisson's scheduler — a drainer parked 90 s on the LLM keeps its lock without waking). Fail → `DEFER(LOCK_HELD)`. Redis down → covered by §11 pause before we get here; if hit anyway → `BREAKER_PARKED(redis)`. From here, **every exit path releases the lock in `finally`** (token-safe release is Redisson's job).

**6. Claim.** Upsert on `(i,r,p,sha,round)`. Increment is **class-aware** (P1-2 — the counter must count errors, not claims):
   - No row → INSERT `status=PROCESSING, error_attempts=1, first_received_at=hdr`.
   - Row `AWAITING_RETRY`, parked hop was **ERROR-class** (per `failure_class`) → UPDATE `status=PROCESSING, error_attempts+=1`.
   - Row `AWAITING_RETRY`, parked hop was **DEFER- or BREAKER-class** → UPDATE `status=PROCESSING` only (**no** `error_attempts` change — the defer SLA and breaker cooldown bound these, not the error budget).
   - Row `PROCESSING`/`POSTING` (stale — we hold the lock, prior owner dead) → UPDATE `status=PROCESSING, error_attempts+=1` (unconditional here — this branch is the poison/hard-crash backstop). [v2: first runs reconciliation §13.3 and bumps `fence`.]
   - Row `SUCCEEDED` for `(i,r,p,sha,round)` (dedup raced) → repopulate marker → `SKIP`.
   - **Terminal row (`FAILED`) + `replay=true`** → `PROCESSING`, reset `error_attempts=1, defer_attempts=0`, restamp `first_received_at`, clear `first_deferred_at` (P2-2 DLQ replay path).
   - **`/rereview` (force)** on an already-`SUCCEEDED` `(i,r,p,sha)` → INSERT a **new row at `round = max(round)+1`**, `status=PROCESSING` (C-4 — a fresh row, so no forbidden `SUCCEEDED→PROCESSING`; the new round escapes the round-1 dedup marker).
   **Backstop only:** `error_attempts > 5` at claim → `TERMINAL(EXHAUSTED_POISON)`. Normally unreachable (the handler dead-letters at attempt 5, §7.2); this catches a poison event that re-claims after exhaustion.

**7. Repo config.** `ghcfg` cache → contents API for `.reviewbot.yml` (github breaker). Missing file → defaults. Parse error → defaults + log (a bad config must not break reviews). Yields `model` (override of installation default), `min_severity`.

**8. Incremental base.** Latest `SUCCEEDED` row for `(i,r,p)` with `head_sha ≠ current` → `base`. No base → full PR diff (first review — same code path, degenerate input). With base → `GET /repos/{r}/compare/{base}...{current}`:
   - `status ∈ {ahead, identical}` → use the delta, with changed-file context (not bare hunks) — the documented middle setting between whole-PR context and pure-delta blindness.
   - `status = diverged` or error (force-push/rebase rewrote history) → **fallback to full PR diff** — semantically correct after a rewrite, not merely safe. ⚠ VERIFY compare-API status values & `pull_request.synchronize` payload fields for a cheaper forced-push signal.

**9. Size guard.** Estimated tokens (chars/4 heuristic — O8) > **0.9 ×** the model's input budget (per-model config map, conservative default **150 000**; ⚠ VERIFY real context windows — trip early: a low guard costs a recoverable advisory, a high guard burns a full-context call that 400s) → v1: `TERMINAL(DIFF_TOO_LARGE)` (+ [v1.5] post an advisory "PR too large for automated review — consider splitting" comment). [v2: chunking §14.5 replaces this branch.]

**10. Generate.** `ReviewGenerator.generate(diff, context, model)` — port in `shared`, `AnthropicReviewGenerator` adapter [v1], resolved per installation (BYOK key decrypted via KMS, never logged). Timeout 120 s, anthropic breaker. Prompt requests structured findings: `{file, summary, severity ∈ CRITICAL|MAJOR|MINOR|NIT, detail}`. **Injection posture (S-3):** the diff is wrapped in explicit data delimiters with the instruction that its content may contain adversarial instructions and must never be obeyed — fencing, not prevention; the residual is stated in the README.
   - `400` → `TERMINAL(LLM_REQUEST)` (identical request fails identically; DLQ is the signal to build chunking).
   - `429` → `DEFER(PROVIDER_THROTTLE)` per headers. `5xx/529/timeout` → `TRANSIENT`. Breaker open → `BREAKER_PARKED`.

**11. Filter.** Drop findings below `min_severity`. Zero remaining → still post a one-line "reviewed {short-sha}: no significant issues" (users need the signal that the bot ran).

**12. Post.**
   a. `UPDATE reviews SET post_attempted_at=now() WHERE id=?` — **before** the HTTP call (§13.2 guard).
   b. **Guard (C-5):** if `post_attempted_at` is non-null at claim **on any claim branch** (a prior attempt may have landed) — run the marker existence check (§13.3 read; the read is v1-scoped even though §13.3 is v2-tagged) first: found → adopt its id → jump to 13. *(Closes the ambiguous-timeout double-post in v1; one GitHub read on retries-after-post only, never on the happy path. Marker matching is author-filtered — see below.)*
   c. `POST /repos/{r}/pulls/{p}/reviews` `{event:"COMMENT", body}` — body = findings + invisible marker `<!-- reviewbot:v1 inst={i} repo={r} pr={p} sha={sha} round={n} sig={HMAC_appSecret(inst,repo,pr,sha,round)} -->` (Markdown renders HTML comments invisibly; the HMAC makes the marker **unforgeable**, S-2). v1 posts **blind** — no pre-check on the happy path; the fence [v2] and dedup already established sole ownership. **All marker matching (here, §13.3, sweep) first filters the GitHub review listing to the app's OWN bot user and paginates to exhaustion** (S-2/P1-5 — GitHub permits reviews from any reader, so an unfiltered/first-page match is forgeable). One review comment per `(reviewed SHA, round)`; old rounds remain as history (decision log #11). **Output defang (S-3):** before posting, LLM output is post-processed to neutralize `@mentions` and bare links — the bot's trusted identity must not be steerable into pinging or phishing.
   - Clean failure (connection refused / clean 5xx) → `TRANSIENT`. Timeout-after-send (ambiguous) → `TRANSIENT` — next attempt hits guard (b). `429` → `DEFER`. Breaker open → `BREAKER_PARKED`.
   [v2: step 12 is preceded by the fenced claim to `POSTING` and the post becomes find-or-create — §13.3.]

**13. Finalize — order is load-bearing (I8):**
   `DB: status=SUCCEEDED, github_review_id, completed_at` → `Redis: SET dedup:{...}:{sha} EX 7d` (best-effort) → `unlock` → outcome `SUCCESS`.
   *(DB first: the durable anchor. Redis second: cache trails truth; a crash between them = Redis miss later → DB fallback repopulates. Unlock after marker: releasing first would let a concurrent same-SHA attempt pass its dedup check before the marker exists. Kafka commit is last of all — it happens via watermark after `markComplete`, so a crash anywhere here redelivers and step 1/6 absorbs it.)*

### 7.2 Outcome handlers (drainer-side)

| Outcome | Actions (then always `markComplete(all drained offsets)`) |
|---|---|
| `SUCCESS` / `SKIP` | nothing further |
| `DEFER(class)` | if `first_deferred_at` is null → set it `= now`. If `now − first_deferred_at > 2 h` → escalate to `TERMINAL(COULD_NOT_SCHEDULE)` (C-3: SLA measures time-being-deferred, **not** time-since-receipt — a record that waited in the backlog during an outage carries no clock until work begins, so pauses can't mass-escalate). Else: row (if claimed) → `AWAITING_RETRY`, `defer_attempts+=1`; **produce to retry** (headers: counters, `not_before` per defer schedule §12, class); unlock if held |
| `TRANSIENT(class)` | **exhaustion decided here** (single source of truth, identical in review-worker and retry-worker §9.3 — C-2). If `error_attempts >= 5` → treat as `TERMINAL(EXHAUSTED_{class})`, building the DLQ envelope from the exception in hand. Else: row → `AWAITING_RETRY`; produce to retry (`not_before = errorSchedule[error_attempts]`, 4 delays for attempts 1–4); unlock |
| `TERMINAL(class)` | build DLQ envelope §10.2 → produce to `pr.review.dlq`; row (if claimed → UPDATE, else UPSERT) → `FAILED(class)` (C-6); unlock |
| `BREAKER_PARKED(dep)` | **no counter changes**; row (if claimed) → `AWAITING_RETRY`; produce to retry, `not_before = now + breaker cooldown (60 s)`, class `BREAKER_PARKED`; unlock. *(Bounded remnant: only records already dispatched when the breaker tripped — ≤ in-flight cap. New intake is stopped by the pause, §11.)* |

INVARIANT (I9): **produce-then-complete** on every hop (main→retry, retry→retry, →DLQ): produce to the destination topic *before* `markComplete`. A crash between them causes a redelivery (absorbed by dedup/claim), never a lost event.

---

## 8. Consumer parallelism engine (review-worker)

Hand-built key-ordered parallel consumer over the raw `KafkaConsumer` (decision log #2: chosen over Confluent Parallel Consumer for interview depth; requirements are a narrow slice — key ordering + watermark commits — and our retry/DLQ machinery replaces the library's). ~400 lines across four classes; all concurrency in the project is confined here (the seam: `processReview` above it is single-threaded-per-PR by construction).

Shared objects (constructor-injected): `Semaphore inFlight(200)`, `OffsetTracker tracker`. Sharing map: **poller ↔ semaphore ↔ tracker ↔ drainers; the dispatcher touches neither** (pure routing).

### 8.1 Poller — one platform thread; the only code touching `KafkaConsumer`

```java
while (running) {
    // Composed pause: backpressure (permits) AND dependency health, one predicate (P1-3/CF-1).
    // MAX_POLL_RECORDS=100, in-flight cap=200, RESUME_THRESHOLD=100 → a full batch always fits,
    // so the intake loop below never blocks mid-drain.
    boolean wantPause = dependencyPause || inFlight.availablePermits() < MAX_POLL_RECORDS;
    boolean canResume = !dependencyPause && inFlight.availablePermits() >= RESUME_THRESHOLD;
    if (wantPause && !paused)     { consumer.pause(assigned); paused = true; }
    else if (canResume && paused) { consumer.resume(assigned); paused = false; }
    // dependencyPause is set/cleared by breaker + health-check state transitions (§11.2).

    var records = consumer.poll(Duration.ofMillis(200));   // empty while paused; heartbeats flow
    for (var r : records) {
        inFlight.acquire();            // never truly blocks: pause-gating ensured budget before intake
        dispatcher.route(r);           // fast, in-memory, non-blocking
    }
    tracker.committable().forEach((tp, wm) ->
        consumer.commitAsync(Map.of(tp, new OffsetAndMetadata(wm + 1)), logOnError));
        // wm + 1: Kafka's committed offset = NEXT record to deliver (classic off-by-one)
}
```

Owns: consumer connection, watermark commits, pause/resume (backpressure + breaker), rebalance callbacks. Never: business logic, blocking I/O, payload inspection beyond the PR key. Cycle time stays in milliseconds forever ⇒ `max.poll.interval.ms` and group liveness are structurally non-issues.

**Rebalance listener** (I7): `onPartitionsRevoked(parts)` → mark parts closed in dispatcher (no new drainer submissions) → grace ~3 s → `commitSync(tracker.committable(parts))` → **`tracker.unregister(parts)`** (drop watermark + islands — stale islands must never contaminate a future re-registration; O5) → return. In-flight drainers for revoked partitions run on as *tolerated zombies*: their `markComplete` is an **offset-accounting no-op on an unregistered partition but still releases the in-flight permit** (I4 — otherwise zombie completions leak permits and slowly strangle backpressure; O5); nothing they complete is ever committed (the coordinator rejects stale-generation commits anyway) ⇒ the new owner re-receives those records and dedup/claim absorbs the overlap — the exact at-least-once window the idempotency stack was built for. `onPartitionsAssigned` → **`tracker.register(tp, consumer.position(tp))`**: baseline watermark = position − 1 (position = next offset Kafka will deliver, so everything ≤ position−1 is already handled; the first delivered record satisfies `off == watermark+1` with no special case); `position()` is a consumer call ⇒ registration runs on the poller thread (I1); re-registration resets that partition's state (O5) → reopen. Shutdown hook: stop intake → drain grace 30 s → `commitSync` → close.

### 8.2 Dispatcher — passive routing component (not a thread)

```java
void route(ConsumerRecord r) {
    var key = PrKey.of(r);                       // installation:repo:pr
    map.compute(key, (k, q) -> {                 // ConcurrentHashMap.compute = atomic
        if (q == null) { q = new PrQueue(); q.add(r); executor.submit(new Drainer(k, q)); }
        else            q.add(r);
        return q;
    });
}
```

Enqueues **everything, always, in arrival order** — never drops, never reorders, never inspects semantics ("dumb queue, smart drain"). Coalescing is domain judgment and lives in the drainer only; a routing layer that "cleans" queues would delete a queued `/rereview` command the moment a push arrives [v1.5 bug avoided by construction]. Queues are ephemeral: exist only while a PR has pending/in-flight work.

### 8.3 Drainer — one task on a virtual thread per PR-with-pending-work

```java
run() {
    while (true) {
        var batch = queue.drainAll();                       // atomic take-everything
        if (batch.isEmpty()) { if (dispatcher.tryRetire(key)) return; else continue; }
        var plan = coalesce(batch);                          // §7 step 0
        plan.superseded().forEach(rec -> complete(rec, SKIP));
        for (var job : plan.jobs()) {                        // v1: ≤1 review job (+ commands v1.5)
            var outcome = core.processReview(job, ctx);      // full §7 flow, blocking style
            complete(job, outcome);                          // outcome handler §7.2 + markComplete
        }
    }
}
```

- `tryRetire` re-checks emptiness **inside** `map.compute()` — a racing record either lands before retirement (drainer loops again) or after (dispatcher finds no entry, spawns fresh). No lost-wakeup window (I10).
- Executor: `Executors.newVirtualThreadPerTaskExecutor()` — unbounded by design; the **semaphore is the throttle** (permits = records in flight: acquired at dispatch, released inside `markComplete`; a hot PR's drainer holding 100 queued records holds 100 permits — correct accounting of swallowed log). Virtual threads: create-per-task-and-discard is the intended Loom pattern (~µs, heap-only); pooling them is an anti-pattern. Blocking I/O parks the virtual thread and frees the carrier — `processReview` stays plain sequential code.
- Concurrency = min(distinct PRs with pending work, 200). Hot-PR serialization is designed behavior, not a scaling defect: same-PR bursts are exactly what must not parallelize, and coalescing dissolves them.

### 8.4 OffsetTracker — contiguous-watermark accounting (the delicate 50 lines)

```java
class OffsetTracker {
    class P { long watermark /* last contiguously complete */; NavigableSet<Long> islands; }
    private final Map<TopicPartition, P> parts = new ConcurrentHashMap<>();
    private final Semaphore inFlight;

    void markComplete(TopicPartition tp, long off) {
        var p = parts.get(tp);
        synchronized (p) {                                   // guards µs of pure computation — no pinning risk
            if (off == p.watermark + 1) { p.watermark = off;
                while (p.islands.remove(p.watermark + 1)) p.watermark++; }   // absorb adjacent islands
            else p.islands.add(off);                                          // gap below → park
        }
        inFlight.release();      // one door: completion and budget-return are one event — permits can't leak
    }
    Map<TopicPartition, Long> committable() { /* snapshot advanced watermarks per partition */ }
}
```

INVARIANT (I3): commit only the highest offset N with **every** offset ≤ N terminal. Committing past a gap = a crash silently loses the gapped record — the one unrecoverable failure in the whole design; the watermark is what makes out-of-order completion compatible with Kafka's single-pointer commit. (Same idea as TCP cumulative ACK.) Property-test with randomized completion orders.

**Baseline & lifecycle (O5):** a partition's tracker state is created by `register(tp, position)` on assignment (watermark = position − 1) and destroyed by `unregister` on revocation. `committable()` reports only partitions whose watermark has advanced since registration/last commit (no redundant re-commit of the baseline). `markComplete` on an unregistered partition releases the permit and does nothing else.

**Invariants summary:** I1 one thread owns the consumer · I2 per-PR serial (dispatcher/drainer structurally + lock cross-instance) · I3 contiguous watermark · I4 permit conservation (acquire@dispatch, release@markComplete) · I5 exactly-one-terminal-outcome per record · I6 pause/resume hysteresis · I7 revocation protocol · I8 finalize order DB→Redis→unlock · I9 produce-then-complete on every hop · I10 retirement handshake.

---

## 9. Retry pipeline (retry-worker)

**Role split:** retry topic = per-message scheduling ("might succeed later while others succeed now"); it is **never** used for infrastructure outages (those pause, §11). Approach A (single topic, in-consumer delay) with an explicit trip-wire to Approach B.

Loop per partition (sequential is sufficient at deploy scale; can adopt §8 machinery if retry volume ever warrants — decision log #10):

1. `poll()`; for the head record read `not_before` header.
2. Not due → `consumer.pause(tp)`; keep polling (heartbeats flow); each cycle check the clock; due → `resume(tp)`. **Never `Thread.sleep` through `max.poll.interval.ms`** — pause/poll is the only correct wait.
3. Due → same `processReview(event, ctx)` core, counters seeded from headers (DB wins on conflict). **Exhaustion is decided in the handler exactly as §7.2 (one core, identical in both workers — C-2): `error_attempts >= 5` → DLQ; else re-produce with next backoff.** Produce-then-commit (I9): success → done; retryable-under-budget → re-produce to retry, then commit; over budget / terminal → DLQ, then commit. Always commit once the record has a durable home — never leave it blocking the partition.
4. Breakers apply here identically: relevant breaker OPEN → pause this consumer too (frozen counters during outages is the point).

**Head-of-line caveat (honest limitation):** within one partition, a 2 h defer at the head blocks a 10 s retry behind it. Tolerable while backoffs are near-uniform; the defer/error split makes long waits more common, so: *first mitigation* = route defers and errors to **separate retry topics** (structurally different delay profiles); *full fix* = tiered delay topics (`retry.30s/.5m/.1h`). **Trip-wire metric:** `retry_delay_excess = actual_start − not_before`; alert when p95 > 60 s → implement the split. Documented, instrumented from day one, not built speculatively.

---

## 10. Dead-letter queue

### 10.1 Failure classification (the `RetryDecision` function — a real component in `shared`)

Route by **kind first** (terminal kinds skip retry entirely — a deleted PR must not burn an hour of pointless retries), then by **count** (retryable kinds that exhaust their budget escalate).

| Class | Kind | Route |
|---|---|---|
| `POISON_DESERIALIZATION`, `PR_NOT_FOUND`, `INSTALLATION_REVOKED`, `LLM_REQUEST` (400), `DIFF_TOO_LARGE` [v1], `MISSING_OR_INVALID_API_KEY` (V-1) | terminal | DLQ immediately |
| `AUTHOR_NOT_PERMITTED` (S-1), `PR_CLOSED`, `SUSPENDED`, `COMMAND_NOT_PERMITTED` [v1.5] | skip (not a failure) | `SKIP` — no DLQ, no retry |
| GitHub/Anthropic 5xx, 529, timeouts, ambiguous post | transient | retry (error schedule) → `EXHAUSTED_*` after 5 |
| **Postgres/Redis op failure** (P1-4) | infra (not per-message) | `BREAKER_PARKED(dep)` — no counters, re-park at health-recheck cooldown |
| provider 429, `RATE_LIMIT`, `LOCK_HELD` | defer (backpressure, not failure) | retry (defer schedule) → `COULD_NOT_SCHEDULE` after 2 h SLA |
| `BREAKER_PARKED` | outage remnant | retry at cooldown, **no counters** |

Ambiguous 403s: inspect headers/body — rate-limit headers ⇒ defer; otherwise permission ⇒ terminal. Status code alone is not the signal.

### 10.2 DLQ envelope (JSON body; `failure_class`, `installation_id` mirrored to headers for filtering)

```json
{ "metadata": { "failure_class": "...", "installation_id": ..., "repo_full_name": "...",
    "pr_number": ..., "head_sha": "...", "error_attempts": n, "defer_attempts": n,
    "exception": {"type": "...", "message": "...", "http_status": ..., "body_snippet": "..."},
    "first_received_at": "...", "dlq_at": "...", "origin": {"topic": "...", "partition": n, "offset": n},
    "trace_id": "..." },
  "original_event": { ...verbatim §3.1 envelope... } }
```

Self-describing: a human six weeks later has the whole story; `original_event` verbatim makes replay possible.

**Poison variant (D-2):** an un-deserializable message has no envelope and no identity — use a raw variant `{ "kind": "RAW", "schema_version": 1, "raw_payload_b64": "...", "original_key_b64": "...|null", "original_headers": {"name": "b64value"}, "origin": {partition, offset}, "exception": {...}, "dlq_at": "..." }` — key and headers captured by the indexer from the `ConsumerRecord` **at index time**, the only moment they are cheaply available (the original ages out of the main topic in 7 d; O10), DLQ key = sentinel `poison:{origin_partition}:{origin_offset}`, and the nullable identity columns in `dlq_entries` (§5.1). **Data hygiene (S-4):** `body_snippet` is length-capped and annotated "may echo customer input"; provider 4xx bodies can quote diff fragments. Never log diff/prompt content anywhere — log sizes and hashes.

### 10.3 Indexer + admin API + resolution

A small consumer thread (in review-worker) copies every DLQ message into `dlq_entries` (§5.1) and commits — the DB is the browse/act surface (cleaner than juggling consumer groups for browsing). Endpoints (internal-only): `GET /admin/dlq?status=NEW&class=...&installation=...` · `POST /admin/dlq/{id}/replay` → re-produce `original_event` **with `replay=true`** (§3.1) so the claim takes the FAILED→PROCESSING path and resets counters (P2-2 — without the flag, replay of an exhausted entry either can't claim or instantly re-exhausts); replay goes through the **full** pipeline (dedup/live-fetch make stale/already-handled work safe — a merged PR replays into a clean `SKIP`); replay also restamps `first_received_at` and clears `first_deferred_at` (C-3) → mark `REPLAYED`. **RAW (poison) entries replay differently (O10):** re-produce the verbatim payload bytes with the **original key and headers** plus a tracing header `dlq_replay=true` — no `replay=true` payload flag (a poison record never deserialized, so no `FAILED` row exists and the claim path is irrelevant; a fixed reader handles it as a fresh event). Playbook: replay ONE poison entry to confirm a deployed fix before the rest · `POST /admin/dlq/{id}/discard` → mark `DISCARDED` · **bulk (P2-5): `POST /admin/dlq/bulk/{replay|discard}` filtered by class + installation + time range** (the `dlq_entries` indexes support it — a flood needs a shaped response, not 400 hand-loops). Resolution playbook: `PR_NOT_FOUND`/`AUTHOR_NOT_PERMITTED`-type → discard (optionally auto, logged) · fixable-bug classes → deploy fix, replay · else manual. **Purge (S-4):** delete resolved rows after 30 d (mirror the topic window). **Alert (P2-5): dimension DLQ depth by `failure_class`** — page on unexpected classes, warn on known-flood classes; a class-blind scalar re-pages through triage and masks a second tenant's entry.

---

## 11. Degraded modes & circuit breakers

**The organizing rule (never mixed):** per-message problems → retry topic + counters. Infrastructure/provider-wide problems → breaker/health-check → **pause partitions** → resume on recovery. Kafka (below) and GitHub's webhook retry (above) are the durable buffers that make pausing free. Counters increment **only while the relevant breaker is CLOSED** — so `error_attempts` measures per-message health, never outage weather; a 2 h GitHub outage must not mass-DLQ a healthy backlog.

### 11.1 Policy table

| Down | Affected | Behavior | Why |
|---|---|---|---|
| Redis | dedup read/write | fail open → DB fallback / skip cache write | cache of durable truth |
| Redis | lock | **v1: unified pause of all processing** | no fence yet ⇒ fail-open unsafe; forced move, cleanly reasoned. [v2 option: fail open on fence+marker] |
| Redis | gateway delivery-dedup | fail open → produce anyway | downstream idempotency absorbs duplicates |
| GitHub | webhooks in | n/a — GitHub buffers & retries | post-outage redelivery sweep |
| GitHub | reads/writes | isolated errors → §10.1 routing; **sustained 5xx/timeout → breaker OPEN → pause** | can't proceed without it; ambiguous post covered by §13.2 guard |
| Anthropic | LLM call | same pattern, independent breaker; 400 → DLQ | no side effect ⇒ free to retry |
| Postgres | everything in workers | health check fails → **pause workers**; gateway keeps ingesting | source of truth; nothing proceeds without it |
| Kafka | consumers | idle + reconnect (nothing to decide) | — |
| Kafka | gateway produce | `503` to GitHub → upstream retry buffer | decision log #7 |

*(v1 Redis policy is deliberately one switch, not four: the lock sits in every path, so partial fail-open just wastes work before stalling at the lock. The per-role analysis above justifies the v2 fail-open option.)*

**Settings isolation (O13):** Postgres down → `/settings/**` returns 503 while webhook ingestion continues unaffected — the gateway's event path and settings path share no DB fate. Never "fix" this with a global DB health gate on the gateway.

### 11.2 Breaker configuration (Resilience4j — pattern from Nygard's *Release It!*; buy, don't build)

One `CircuitBreaker` per provider: `github`, `anthropic` (+ Spring health checks acting as breakers for `postgres`, `redis`).

```yaml
resilience4j.circuitbreaker.instances.github:      # anthropic: same shape
  slidingWindowSize: 20
  minimumNumberOfCalls: 10
  failureRateThreshold: 50        # OPEN when ≥50% of last 20 calls fail
  waitDurationInOpenState: 60s    # cooldown before HALF_OPEN probe
  permittedNumberOfCallsInHalfOpenState: 2
  ignoreExceptions: [TerminalReviewException]   # CRITICAL: 404s/permission errors say nothing
                                                # about provider health — must not trip the breaker
  recordExceptions: [IOException, TimeoutException, ServerErrorException]
```

**Wiring (the interesting ~30 lines you write):** subscribe to breaker state-transition events → any *required* dependency OPEN ⇒ poller sets `dependencyPause=true` (both workers) → partitions paused (heartbeats continue). CLOSED (all required deps healthy) ⇒ `dependencyPause=false`. **Resume requires `!dependencyPause` AND permits (§8.1 composed predicate — P1-3);** without this, the poller resumes intake during an outage and churns the whole backlog onto the retry topic. In-flight records at trip time exit via `BREAKER_PARKED` (§7.2) — a bounded remnant (≤ in-flight cap) parked on retry at cooldown with **frozen counters**.

**Recovery probes (P2-1 — REQUIRED; without them an open breaker never closes):** paused workers make no calls, so a breaker driven only by record traffic can never observe the success it needs to close — every outage would end in a manual pod restart. Each provider breaker gets a scheduled probe (`@Scheduled`, ~15 s) issuing a trivial call through the breaker (GitHub `GET /rate_limit`; a minimal Anthropic request). Probes run **regardless of consumer pause state** — they are the only call source while paused, and their successes drive HALF_OPEN→CLOSED, which clears `dependencyPause` and resumes intake. **Credentials (O12):** GitHub probe = app-JWT `GET /rate_limit` (installation-independent; ⚠ VERIFY JWT acceptance and that it consumes no rate limit); Anthropic probe = a **dedicated ops-owned probe key** in platform secret config (BYOK leaves no platform key; a customer's key is never used for platform ops), cheapest authenticated call (⚠ VERIFY). **Probe 401/403 is a credential failure, EXCLUDED from breaker counting** (it says nothing about provider health) → dedicated "probe credentials broken" alert — otherwise a revoked probe key masquerades as an eternal outage and permanently pauses the fleet. Interval: 15 s while not-CLOSED; may relax to 60 s or stop while CLOSED (real traffic drives a closed breaker).

**Health-check breakers (P2-3):** Postgres/Redis health checks act as breakers but need anti-flap semantics of their own — **N=3 consecutive failures to pause, M consecutive successes over ≥30 s to resume** (a single failed ping must not pause the fleet; a single success must not resume it). Every breaker transition = metric + alert (§15).

---

## 12. Timing & configuration reference

Derivation chain: **bound the work with client timeouts → the lock outlives the holder via watchdog renewal → Kafka timers are structural non-issues** (poller never blocks).

| Setting | Value | Why |
|---|---|---|
| GitHub HTTP | 2 s connect / 10 s read | bounds every call; failure classifies per §10.1 |
| Anthropic call | 120 s total | big-diff reviews legitimately run long; caps worst-case lock hold (~150 s: fetch+LLM+post+finalize) |
| Postgres / Redis ops | 5 s / 2 s | fast-fail into health-check/pause path |
| Lock | Redisson watchdog: TTL 30 s, renew ~10 s (acquire **without** leaseTime — passing one disables the watchdog) | lock lifetime = holder lifetime; crash frees ≤30 s; kills the fixed-TTL zombie source. Residual: whole-process stall > ~20–30 s (GC/VM freeze) → lapse → v1 accepts (rare; quantified in §13.4), v2 fence closes |
| `session.timeout.ms` / `heartbeat` | 45 s / 3 s | crash detection; poller always heartbeats |
| `max.poll.interval.ms` | 300 s default | poller cycles in ms; a violation now means a real bug — alert, don't raise |
| Poll timeout / `max.poll.records` | 200 ms / **100** | fast loop; `max.poll.records` ≤ resume threshold so a full batch always fits permits (intake never blocks mid-drain — CF-1) |
| In-flight cap / resume threshold | 200 / **100** (`availablePermits() >= 100`) | sized to external API budgets, not CPU; pause when `availablePermits() < 100`, resume at `>= 100` — one unit, no inversion (CF-2) |
| Gateway produce-await deadline | **5 s → 503** | under GitHub's 10 s webhook timeout; produce may complete post-503 (duplicate absorbed) — prevents thread exhaustion under Kafka-slow (P4-1) |
| Lock `tryLock` wait | 2 s | brief wait then `DEFER(LOCK_HELD)`; never blocks a drainer long |
| Shutdown drain grace | 30 s (option: SIGTERM → stop intake, wait ≤150 s for in-flight → `commitSync`) | graceful deploy; longer wait avoids re-billing in-flight LLM calls |
| Recovery probe interval (P2-1) | ~15 s per provider | drives HALF_OPEN→CLOSED while workers are paused |
| Health-check cadence / breaker (P2-3, O11) | checks every **5 s**; **3** consecutive fails → pause; **3** consecutive successes AND span ≥ 30 s → resume (span clock starts at first success after last failure) | pause detected ≤ ~15 s; resume ≈ 7 unbroken passes — trip fast, recover cautiously |
| Token estimator / model budget (O8) | chars/4; per-model input budget, default **150 000**; guard trips > 0.9 × budget | trip early — low guard = recoverable advisory; high guard = burned full-context 400. ⚠ VERIFY real windows |
| `body_snippet` cap (O3) | **2048 chars**, literal `…[truncated]` suffix | bounds accidental diff echo (S-4); keeps DLQ envelopes cheap |
| Author-permission cache (O7) | in-memory per worker, 5 min TTL, bounded | staleness bound: revoked author reviewable ≤ 5 min |
| Installation-token cache (O6) | per installation; refresh ~5 min **before** expiry (~1 h tokens) | one forced-refresh retry on 401; exchange-401 = credential alert |
| Error backoff | 4 delays (10 s, 1 m, 5 m, 15 m) for attempts 1–4; attempt 5's failure dead-letters in the handler | "5 attempts" = 5; exhaustion decided handler-side (C-2) |
| Defer backoff | LOCK_HELD 30 s flat · RATE_LIMIT 1 m→2 m→5 m (then 5 m capped) · 429 = provider `Retry-After` | deferrals aren't failures — generous |
| Defer SLA | 2 h from **`first_deferred_at`** (first DEFER, not receipt) → `COULD_NOT_SCHEDULE` | time-bound, never count-bound; a backlogged record carries no clock until work begins (C-3) |
| `BREAKER_PARKED` `not_before` | +60 s (= breaker cooldown) | remnant re-checks after probe window |
| Stale-`PROCESSING` alert | untouched 10 min | self-heals via redelivery; alert catches systemic issues |
| Redis TTLs | delivery 24 h · dedup 7 d · ghcfg 5 min | §5.2 |
| Rate-limit cap | 5 concurrent per installation | config; approximate is fine (backpressure, not correctness) |

---

## 13. Side-effect idempotency

**The problem:** the pipeline guarantees at-least-once execution, and the load-bearing side effect (posting to GitHub) targets a third party that offers **no idempotency key and no conditional create** (⚠ VERIFY against current GitHub review-API docs; design assumes absence). Exactly-once against such an API is provably unattainable — the correct posture is *at-least-once with idempotent effects*, stated openly.

### 13.1 The layered defense (who catches what)

| Layer | Guards | Catches |
|---|---|---|
| Partition key + drainer serialization | ordering within instance | everyday same-PR concurrency |
| Redisson lock + watchdog | cross-instance mutual exclusion, lifetime = holder's | rebalance overlap; slow-worker zombie (watchdog: lock can't expire under a live worker) |
| Dedup marker (per-SHA) + DB fallback | "already done" | redeliveries, crash-after-finalize |
| Claim + attempt counters | "who started, how often" | poison loops, stale takeover detection (lock held + row PROCESSING ⇒ prior owner dead) |
| `post_attempted_at` guard [v1] §7.12b | "a post may have landed" | ambiguous-timeout double-post — one GitHub read, retries-after-post only |
| Fence token [v2] | monotonic single-writer authorization in **our DB** | zombie finalizing stale work (internal state) |
| Embedded marker + find-or-create [v2] | idempotent **external** effect | the sliver the fence can't reach: an HTTP call already in flight from a worker being fenced |
| Cleanup sweep [v2] | detect-and-remove via shared marker | the residual simultaneous-create (§13.4) |

Fence guards internal state; marker guards the external effect — different gaps, both required.

**Marker matching is author-authenticated (S-2):** every marker search (the §7.12b guard, v2 reconciliation, find-or-create, and the sweep) first filters the GitHub review listing to the app's **own bot user** and verifies the marker's HMAC before matching — GitHub permits reviews from any reader, so an unfiltered/unsigned marker is forgeable (an attacker could suppress the bot's review or, via the sweep, delete it). Listings paginate to exhaustion (P1-5). **Bot identity (O9):** the bot login used for author-filtering is configured and **verified at startup** against the App identity endpoint — fail-fast on mismatch (turns a silent S-2 filter regression, where the filter matches nothing and every reconciliation "finds no comment", into a boot error) — then cached for the process lifetime (⚠ VERIFY endpoint).

### 13.2 v1 posture (build first)

Lock-with-watchdog + dedup + finalize ordering (I8) + claim logic + the `post_attempted_at` guard. Correct for: redelivery, crash-before-commit, crash-after-post-before-finalize (guard finds the ghost post), rebalance overlap (zombie completions never commit — generation fencing; new owner reconciles via claim+guard). **Accepted v1 residual:** a true zombie (two live workers on one PR) arises if the lock is lost under a live holder — either a whole-process stall > watchdog window (GC/VM freeze), **or a Redis failover/restart that drops the lock key** (V-3 — the watchdog cannot renew a key the store lost; more probable than the GC pause). Consequence is a duplicate comment, not data loss; v2 (fence + find-or-create + sweep) closes it. Optionally: treat a Redisson failed-renewal signal as lock-lost and abort before posting.

### 13.3 v2 hardening (fully specified — build-ready)

- **Fence issuance:** on claiming a *stale* row (§7.6 stale branch): `UPDATE reviews SET fence = fence + 1, status='PROCESSING', error_attempts = error_attempts + 1 WHERE (i,r,p,sha)=? RETURNING fence` → my token. Monotonic because the row update is serialized by the DB. Fresh rows: fence stays 0 (no competitor — the check below passes trivially; one code path, guard clauses no-op on the happy path).
- **Reconciliation (before LLM, stale claim only):** GitHub existence check via marker — found → adopt id, jump to finalize (repair records, skip work: the *skip-not-patch* default) → else proceed. This is the **early check = LLM-cost optimization**; it may miss a post that lands after it runs — that is the late layer's job.
- **Fenced posting claim:** `UPDATE reviews SET status='POSTING', post_attempted_at=now() WHERE (i,r,p,sha)=? AND fence <= :mine AND status='PROCESSING'` → **0 rows ⇒ lost (newer owner exists) ⇒ abort without committing** — the winner commits; a fenced-out loser never retries-and-bumps (monotonic ⇒ no livelock). Kafka's generation fencing independently rejects a partition-loser's offset commit.
- **Find-or-create at post time (the correctness layer):** list PR reviews → find marker `sha={sha}` → found ⇒ adopt (skip create) · absent ⇒ create. Executed as one tight operation at the last instant — it sees posts that landed after the early check.
- **Cleanup sweep:** on each review of a PR (+ optional low-frequency job): two **bot-authored** comments with identical `(sha, round)` markers ⇒ delete the newer duplicate (scoped to `(sha, round)` so a legitimate `/rereview` at a higher round is never deleted — C-4; author-filtered so a forged marker is never acted on — S-2). Duplicates are *detectable and self-healing* even when not preventable.
- **Unlocked option:** with the fence live, Redis-down may switch from pause to fail-open (lock skipped; fence+marker carry correctness; cost = occasional wasted parallel LLM work). Config flag, default off.

### 13.4 The accepted residual (state this in the README — it reads as senior, because it is)

> At-least-once processing with idempotent effects. The only residual is a sub-millisecond simultaneous find-or-create against GitHub's API, which offers no idempotency primitive to prevent it; duplicates from that window carry identical markers and are detected and removed by reconciliation rather than prevented by cross-worker coordination on every post — a deliberate trade of a rare, cosmetic, self-healing blemish against permanent complexity and latency on the hot path.

---

## 14. Product features

### 14.1 BYOK & settings [v1 — the app is inert without keys]
GitHub App **Setup URL** → post-install redirect to a settings page. **Authentication (D-3):** the page requires **GitHub OAuth**; after login, verify the authenticated user has admin access to the installation, and sign the setup-redirect `state` parameter — without this, an attacker enumerates `installation_id`s and overwrites victims' keys. Flow: paste Anthropic key → validate with a cheap test call → **envelope-encrypt** → store per `installation_id`; same page for rotation and `default_model`.

**Encryption assembly (S-5 — specify, don't leave to guesswork):** per-installation envelope encryption storing `{encrypted_DEK, nonce, ciphertext, key_version}` (not one opaque BYTEA blob); **random 96-bit nonce per encryption** (GCM nonce reuse forfeits the whole mode — a §19 trap); DEK decrypted via KMS **only in the adapter call path**; `key_version` enables future KMS-master-key rotation/re-encryption. Keys never in repo files, never logged (including on the settings path — redact request bodies and validation-failure messages), decrypted only at point of use.

**Hosting & privilege split (O13):** settings lives in the **gateway** at `/settings/**` (the public surface already exists; no fourth deployable; decision log #8 intact — its rationale is the event path, which stays DB-free). KMS least-privilege: gateway IAM = `kms:GenerateDataKey` **only** (encrypt side); workers = `kms:Decrypt` **only** — a compromised gateway cannot decrypt stored keys; a compromised worker cannot mint-and-swap them. Auth scoping: the webhook HMAC filter applies to `/webhook` only; `/settings/**` = the D-3 GitHub OAuth flow **plus CSRF protection on the form POST plus a basic rate limit** (public, credential-accepting surface).

### 14.2 `ReviewGenerator` port [v1]
`interface ReviewGenerator { Review generate(Diff d, ReviewContext c, ModelRef m); }` in `shared`; `AnthropicReviewGenerator` is the only v1 adapter; a factory resolves per installation/repo config. Hexagonal seam: a second provider later = one class, zero pipeline changes. Build the seam, ship one adapter.

### 14.3 Incremental review [v1] & severity filter [v1]
Specified inline: §7.8 (compare API, diverged→full fallback, changed-file context) and §7.11 (severity threshold from `.reviewbot.yml`, always post at least a one-liner). Comment strategy: one review per reviewed SHA; per-SHA markers make this compose with idempotency unchanged.

### 14.4 Commands [v1.5]
Gateway accepts comment webhooks (§6.1.2, filtered on `issue.pull_request` presence); parses leading `/rereview` | `/model <name>`; **ignore the bot's own comments** (sender == app bot user — the classic infinite-loop bug). **Command transport (P4-3):** `issue_comment` payloads carry no head SHA → `head_sha` is optional for command types and the **worker** resolves it via live fetch. **The permission check runs in the WORKER, not the gateway** (the gateway holds no GitHub token and has a sub-second budget): via the collaborators API; unauthorized → `SKIPPED(COMMAND_NOT_PERMITTED)` (no DLQ) + optional advisory.

`/rereview` → `REREVIEW_COMMAND`, `force=true` → worker claims the **next round** for the current head SHA (C-4); old rounds retained as history. `/model <name>` (D-1) → **sets `installations.default_model`**, acked with a comment; applies to subsequent reviews (repo-scoped override already exists via `.reviewbot.yml`); the argument is **validated against a model allowlist at parse time** (S-6), rejected with an advisory. Commands survive coalescing (§7.0) and run in queue order.

### 14.5 Diff chunking [v2]
Replaces §7.9 terminal branch: split by file groups within token budget → per-chunk LLM calls → merge findings (dedupe across chunks; note cross-chunk blindness as a documented limit). Sequenced after incremental review because incremental makes oversized diffs rare (mostly first reviews of huge PRs) — the v1 story is handle (DLQ + advisory) → rarify (incremental) → solve (chunking).

---

## 15. Observability

Micrometer → Prometheus → Grafana; OpenTelemetry traces with `trace_id` propagated through headers and the DLQ envelope. Structured JSON logs (never log payloads containing keys).

**Metrics:** consumer lag per partition (main/retry/DLQ) · `in_flight` gauge · **`pause_state` gauge labeled `reason=backpressure|github|anthropic|redis|postgres`** (P2-7 — "why are we paused?" answered by one label) · watermark lag (max in-flight offset − watermark) · outcomes counter by `{SUCCESS,SKIP,DEFER,TRANSIENT,TERMINAL,BREAKER_PARKED} × class` · breaker state per provider · DLQ depth **dimensioned by `failure_class`** (P2-5) + `dlq_entries` NEW count · **`dlq_indexer_lag`** (V-4 — indexer dead/lagging while depth grows = stale browse surface) · `retry_delay_excess` (§9 trip-wire) · LLM latency/tokens/cost estimate per review · GitHub rate-limit-remaining gauge per installation · lock acquisition failures · stale-PROCESSING age · gateway: webhook latency, non-2xx count.

**Alert classification principle (O14):** *page* = the system/platform is broken and an operator acts now; *warn* = a tenant or input has a problem that self-serves or waits. Sort every future class by this rule.

**Alerts:** DLQ depth > 0 for 5 min, **dimensioned by class** (P2-5): **page** on `POISON_DESERIALIZATION`, `EXHAUSTED_*`, and `LLM_REQUEST` at volume > 2/1 h (a 400 means **we** built an invalid request — platform bug; the volume floor tolerates lone estimator misses until v2 chunking); **warn** on `PR_NOT_FOUND`, `INSTALLATION_REVOKED`, `MISSING_OR_INVALID_API_KEY`, `DIFF_TOO_LARGE`; `COULD_NOT_SCHEDULE` warns per entry and **escalates to page at > 10/1 h** (one busy tenant = a business-hours conversation; a cross-tenant spike = systemic starvation) · any breaker OPEN (page) · "probe credentials broken" (page — O12) · **outcome-rate (P2-4, O14): SUCCESS < 50 % over 5 min with a ≥ 20-outcome floor (deploy-scale honest — silent on night-trickle); failure spike when current > max(3 × 1 h baseline, 5 absolute) (baseline floor prevents first-failures-after-quiet paging)** · main-topic lag growing 10 min (warn) · `retry_delay_excess` p95 > 60 s (warn → Approach B step 1: split defer/error topics) · stale PROCESSING > 10 min (warn) · `dlq_indexer_lag` (warn — V-4) · Redis/Postgres health (page) · gateway 5xx rate (warn). All thresholds are ops-tunable **starting values, reviewed after two weeks of real traffic**.

---

## 16. Scaling guide (design 1000/s · deploy ~10/s)

**The real ceiling first:** at high scale the binding constraint is **external API rate limits** (GitHub per-installation; Anthropic per-account/key). Infrastructure below scales past 1000/s with the steps in this table; the throttle/defer/breaker machinery is the primary control system because the providers, not the cluster, set true throughput. (BYOK conveniently shards Anthropic limits per customer.)

| Component | Trigger (measured) | Next step | Ceiling of that step |
|---|---|---|---|
| review-worker | sustained lag with in-flight saturated | raise in-flight cap (if APIs allow) → add instances | instances ≤ partitions |
| Kafka partitions | instances == partitions and still lagging, work parallelizable | topic-swap migration §4.4 (e.g. 6→24) | broker capacity (add brokers) |
| Rate limiting | `idx_reviews_rate` count query visible in DB load | Redis counters + TTL + periodic DB-reconciliation sweep | — |
| Retry delays | `retry_delay_excess` p95 > 60 s | split defer/error topics → tiered delay topics | — |
| Redis | latency / memory pressure | cluster mode; [v2] fail-open flag reduces criticality | — |
| Postgres | write latency (attempt updates ≈ events/sec) | batch counter writes → read replica for counts → partition `reviews` by installation | — |
| Gateway | CPU / p99 near 1 s | horizontal (stateless) | — |
| LLM cost/latency | per-review economics | smaller default model, prompt-side diff pruning, per-plan model tiers | provider limits |

Throughput math that justifies the architecture: sequential = 1/review-latency per consumer (~0.3–0.5/s ⇒ single digits total — inadequate even at modest real load, which is why async is v1, not an optimization). Async: ~150 in-flight × ~3 s/review ≈ 50/s per instance ⇒ 1000/s ≈ 20 instances / ≥20 partitions — sane, versus ~3000 partitions without async.

---

## 17. Failure-mode matrix (failure → signal → mechanism → outcome)

| # | Failure | Detected by | Handled by | Outcome | Ver |
|---|---|---|---|---|---|
| 1 | GitHub redelivers a webhook | `delivery:` SETNX | gateway dedup | 200, no duplicate event | v1 |
| 2 | Producer retry duplicate | broker sequence nos | `enable.idempotence` | single record in log | v1 |
| 3 | Duplicate semantic event (CI storm, force-push to same SHA) | dedup marker (per SHA+round) / DB row | §7.1/§7.4 | SKIP, no work | v1 |
| 4 | Burst of events for one PR | drainer queue | coalescing §7.0 | one review of newest SHA; rest SUPERSEDED | v1 |
| 5 | Crash before offset commit (work finalized) | redelivery | dedup marker | SKIP on redelivery | v1 |
| 6 | Crash mid-LLM (claimed, nothing posted) | redelivery → lock free + row PROCESSING | claim stale-branch → redo | review completes; attempts counted | v1 |
| 7 | Crash after post, before finalize | redelivery → `post_attempted_at` set | §7.12b guard (marker read) | adopt ghost post, repair records — no duplicate | v1 |
| 8 | Ambiguous post timeout | TRANSIENT → retry | same guard on retry | resolved: adopt or post | v1 |
| 9 | Rebalance overlap / wedged worker evicted | generation fencing + lock | zombie commits rejected; new owner reconciles via claim | at-least-once absorbed; no lost events | v1 |
| 10 | Whole-process stall > watchdog (true zombie) | — (residual) | v1: accepted, rare-squared · v2: fence + find-or-create + sweep | v1: possible duplicate comment · v2: converges to one | v1→v2 |
| 11 | Simultaneous find-or-create sliver | — (residual) | identical markers → cleanup sweep | detect-and-remove; §13.4 statement | v2 |
| 12 | Poison message (deserialization) | validation on consume | classifier → DLQ | `POISON_DESERIALIZATION`, alert | v1 |
| 13 | PR deleted / installation revoked | live-fetch 404/permission | terminal classes | DLQ (discardable) | v1 |
| 14 | Oversized diff | token estimate / LLM 400 | `DIFF_TOO_LARGE` → DLQ (+advisory v1.5) → chunking | handled → rarified → solved | v1→v2 |
| 15 | GitHub/Anthropic blip (isolated 5xx) | per-call failure | error schedule → retry topic | succeeds on re-attempt | v1 |
| 16 | Provider outage (sustained 5xx) | breaker OPEN | pause both workers; `BREAKER_PARKED` remnant; counters frozen | backlog waits in Kafka; drains on CLOSED; zero DLQ drift | v1 |
| 17 | Provider throttle (429) | rate-limit headers | defer per `Retry-After` | deferred per Retry-After; DLQ'd only past the 2 h scheduling SLA (see row 18) | v1 |
| 18 | Tenant floods (noisy neighbor) | rate-limit count | DEFER(RATE_LIMIT), per-installation cap | isolation; 2 h SLA then `COULD_NOT_SCHEDULE` | v1 |
| 19 | One retryable failure among a concurrent batch | outcome routing | produce-to-retry = terminal outcome for main | watermark advances; no head-of-line block | v1 |
| 20 | Redis down | health/breaker | unified pause (v1) · fail-open option (v2) | paused, lossless resume · degraded-fast | v1/v2 |
| 21 | Postgres down | health check | pause workers; gateway keeps ingesting | backlog in Kafka; auto-resume | v1 |
| 22 | Kafka down | produce/poll errors | gateway 503 → GitHub retries; consumers idle | upstream buffer holds; sweep after | v1 |
| 23 | Worker down > retention (7 d) | — | operational alerting long before | only true loss mode; monitored | — |
| 24 | Retry head-of-line (long defer blocks short retry) | `retry_delay_excess` | trip-wire → split topics → tiers | bounded, instrumented | v1→scale |
| 25 | Bot replies to itself | sender check | ignore own comments | no loop | v1.5 |
| 26 | Revert to a superseded-but-unreviewed SHA | no poisoning marker exists (P1-1) | dedup means reviewed-only | current head reviewed normally | v1 |
| 27 | Kafka slow (not down) | 5 s gateway await | 503 → GitHub redelivers | no thread exhaustion; succeeds on recovery | v1 |
| 28 | Healthy-but-throttled tenant | class-aware claim increment (P1-2) | defers don't burn error budget | bounded by 2 h SLA, not 5 errors | v1 |
| 29 | Provider outage never ends the pause | recovery probe (P2-1) | scheduled probe drives breaker→CLOSED | auto-resume, no manual restart | v1 |
| 30 | DLQ replay of an exhausted entry | `replay=true` claim path (P2-2) | FAILED→PROCESSING + counter reset | re-runs through full pipeline | v1 |
| 31 | Unauth fork-PR author drains BYOK budget (public repo) | author-authorization step (S-1) | SKIPPED(AUTHOR_NOT_PERMITTED) before any spend | owner's key protected | v1 |
| 32 | Forged idempotency marker (suppress/delete review) | author-filter + HMAC (S-2) | unfiltered/unsigned marker rejected | real review posted | v1 (filter) / v2 (sweep) |
| 33 | Missing/invalid BYOK key (onboarding) | key-presence check (V-1) | TERMINAL(MISSING_OR_INVALID_API_KEY) + advisory | no paging storm; user prompted | v1 |
| 34 | Prompt injection via hostile diff | fenced-data prompt + output defang (S-3) | posture, not prevention; README residual | advisory review; injectable, stated | v1 |
| 35 | No per-tenant containment during a flood | suspended kill switch (P2-6) | admin sets suspended → SKIP at step 2 | flood stopped at source | v2 (hardening) |
| 36 | Cross-tenant settings tampering | GitHub OAuth + signed state (D-3) | visitor must have installation admin | keys not overwritable by strangers | v1 |
| 37 | Handler's own produce/DB fails (V-2) | held permit + backpressure | retry produce with backoff holding permit; DB writes best-effort (claim reconciles) | intake stalls (desired); alert `handler_produce_failure` | v1 |

---

## 18. Testing strategy

- **Unit — OffsetTracker (highest priority):** property tests — random completion orders over random offset sets ⇒ invariant: committed watermark never exceeds contiguous prefix; permits conserved (I3, I4). Baseline/lifecycle (O5): register-from-position baseline; `markComplete` after revoke/unregister releases the permit and never surfaces in `committable()`.
- **Unit — dispatcher/drainer:** concurrent `route()` for one key ⇒ exactly one drainer (I2); retirement race (record lands during `tryRetire`) ⇒ never stranded (I10); coalescing: superseded resolved, commands survive [v1.5].
- **Unit — classifier:** every §10.1 row, including ambiguous-403 header inspection.
- **Integration (Testcontainers: Kafka, Postgres, Redis · WireMock: GitHub, Anthropic):** happy path e2e · duplicate delivery ⇒ one review · crash-sim (kill worker container mid-flight) ⇒ redelivery, no lost event, no duplicate (guard) · rebalance (start 2nd worker mid-load) ⇒ per-PR order preserved, watermark sane · breaker: WireMock 503-storm ⇒ pause, frozen counters · **breaker recovery: flip WireMock healthy ⇒ system resumes and drains WITHOUT pod restart (P2-1) — asserts the recovery probe closes the breaker** · retry: `not_before` honored via pause/resume, no `max.poll.interval` violation · DLQ: poison ⇒ raw-variant envelope complete ⇒ **replay of an EXHAUSTED_* entry (not just poison) ⇒ FAILED→PROCESSING + counter reset ⇒ succeeds (P2-2)** · **marker search paginates past page 1 (multi-page WireMock) — no duplicate post on a long PR (P1-5)** · **forged-marker review by a non-bot author is ignored (S-2)**.
- **Contract:** envelope tolerant-reader (extra fields ignored; missing-required ⇒ POISON).
- **Gate:** JaCoCo ≥ 80 %; the engine classes (§8) target ~100 % branch.

---

## 19. Implementation notes & known traps

1. **Virtual-thread pinning (Java 21):** blocking inside `synchronized` can pin the carrier. Use `ReentrantLock` around anything that might block; the tracker's `synchronized` is fine (µs of pure computation). Diagnose with `-Djdk.tracePinnedThreads=full`. (Largely fixed in Java 24 / JEP 491 — you're on 21, discipline applies.)
2. **Commit `watermark + 1`** — committed offset = next-to-deliver. The classic off-by-one; now you hit it zero times.
3. **Redisson watchdog only runs when `tryLock` is called *without* `leaseTime`.** Passing a lease disables renewal and silently reintroduces the fixed-TTL zombie. Release in `finally`; Redisson's Lua release is token-safe (only the holder can unlock — the hand-rolled `DEL` bug you never write).
4. **Produce-then-complete (I9) and finalize order (I8)** are load-bearing orderings, not style. Any reordering reopens a designed-out failure mode.
5. **Jackson:** `FAIL_ON_UNKNOWN_PROPERTIES=false` everywhere; explicit required-field validation after bind.
6. **HMAC:** constant-time compare (`MessageDigest.isEqual`).
7. **Secrets:** keys encrypted at rest (KMS), decrypted in-adapter only, never logged, never in `.reviewbot.yml`.
8. **Bot loop guard:** drop comment events where sender == app bot user *before* parsing.
9. **Breaker exception lists:** terminal classes in `ignoreExceptions` — a wave of 404s must not pause the system.
10. **⚠ VERIFY at implementation:** GitHub review-API idempotency absence (§13 assumes none) · compare-API status values & synchronize-payload force signal (§7.8 — fallback specified either way) · webhook redelivery API path/window (§6) · Anthropic error codes incl. 529.
11. **Marker matching (S-2/P1-5):** never match a marker without first filtering the GitHub review listing to the app's OWN bot user AND verifying the marker HMAC; paginate the listing to exhaustion (a first-page-only scan reopens the duplicate-post hole on long PRs).
12. **GCM nonce (S-5):** a fresh random 96-bit nonce per encryption. Nonce reuse under one key forfeits confidentiality AND authenticity — the classic crypto-implementation bug. Store `{encrypted_DEK, nonce, ciphertext, key_version}`.
13. **Header codecs live in `shared`** and are used by both workers — encode `not_before`/`first_received_at`/`first_deferred_at` as epoch-ms strings, counters as decimal-int strings. A codec mismatch between workers is a silent interop bug.
14. **`dedup:` means reviewed-and-posted only (P1-1)** — never write it for a skipped or superseded SHA, or a later revert to that SHA is silently suppressed.
15. **Local dev:** docker-compose (Kafka KRaft single broker with RF=1/ISR=1, Postgres, Redis, WireMock); `smee.io` or ngrok for webhook tunneling. **Flyway (O1):** runs at app startup locally; in k8s, a dedicated migration Job (or init container) runs before rollout so multi-instance workers never start against a mid-migration schema.
16. **The App private key (PEM) is a secret with BYOK-grade handling (O6):** secret config only, never logged, never in the repo — it signs for ALL installations. Same rules for the Anthropic ops probe key (O12).

---

## 20. Roadmap → epics & tickets

**V1 — correct core (build in this order; system runs end-to-end from E5 on):**
- **E1 shared:** envelope + validation · PR key + Redis key builders · failure classifier + outcomes · `ReviewGenerator` port · config models. *(5 tickets)*
- **E2 infra:** compose stack · Flyway V1 (§5.1) · topic provisioning · CI (build/test/lint/JaCoCo). *(4)*
- **E3 gateway:** HMAC filter · event filter+envelope · delivery dedup (+fail-open) · producer+503 path · k8s. *(5)*
- **E4 engine:** OffsetTracker+tests · dispatcher+drainer+retirement · poller (backpressure, commits) · rebalance listener+shutdown · engine integration test. *(5)*
- **E5 pipeline:** steps 1–6 (dedup/rate/**author-authz S-1**/**key-presence V-1**/fetch/lock/class-aware-claim) · Anthropic adapter+BYOK decrypt · severity+post+**author-filtered HMAC marker (S-2)** · finalize+outcome handlers (class-aware exhaustion C-2/P1-2, defer-SLA C-3, BREAKER_PARKED(dep) P1-4) · guard §7.12b · incremental review §7.8 · e2e happy path. *(8)*
- **E6 retry:** pause-until-due consumer · counters/backoff/promotion · `retry_delay_excess` metric. *(3)*
- **E7 DLQ:** envelope builder · indexer→`dlq_entries` · admin list/replay/discard · alert. *(4)*
- **E8 degraded:** breakers+config · pause wiring (poller flag) · `BREAKER_PARKED` path · DB/Redis health-pause · outage integration test. *(5)*
- **E9 features:** incremental (§7.8) · settings page+KMS · `.reviewbot.yml` loader. *(3)*
- **E10 observability:** metrics · dashboards · alert rules. *(3)*
- **E11 deploy:** EKS manifests/secrets · MSK/RDS/ElastiCache setup · runbook (pause states, DLQ playbook, redelivery sweep). *(3)*

**V1.5 — interaction:** comment webhooks+command parser (+bot-guard, permission check) · `/rereview` force-flag path · `/model` · advisory comment for `DIFF_TOO_LARGE` · admin polish. *(5)*

**V2 — hardening:** fence issuance+fenced POSTING claim · reconciliation-before-LLM · find-or-create post · cleanup sweep · Redis fail-open flag · diff chunking (split/merge) · zombie chaos test. *(7)*

Definition of done, v2 = application-ready: failure matrix rows all demonstrably handled (tests referenced), README with architecture diagram + §13.4 statement, SCALING content (§16) published, demo repo with the bot live.

---

## Appendix — Decision log (alternatives considered; interview material)

1. **6 partitions, not 3/12** — consumer-parallelism ceiling + failover headroom + clean divisibility; throughput lives in async concurrency; count is near-immutable (migration cost) so err slightly high. Rejected 12: unused flexibility.
2. **Hand-built parallel consumer over Confluent Parallel Consumer** — narrow requirement slice (key-order + watermark), our retry/DLQ supersedes the library's, and the component is the interview centerpiece. Library documented as the contained fallback (core untouched either way).
3. **JSON over Avro+registry** — monorepo shared types = the contract enforcement a registry provides across teams; human-readable DLQ is a requirement; size irrelevant vs LLM cost. Trigger to Avro/Glue: external consumer team or measured bandwidth cost.
4. **DB-derived rate limiting over Redis counters** — derived state is self-healing (crashed INCR-without-DECR leaks a tracked counter forever; a stale PROCESSING row reconciles itself); approximate limiting suffices. Redis counters + reconciliation sweep = documented scale step.
5. **Pause over retry-routing for outages** — retry is per-message scheduling; an outage inverts its premise (nothing succeeds now, everything will later); Kafka's log *is* the waiting room. Movement during an outage is heat, not progress. Prevents counter-burn mass-DLQ.
6. **Redisson lock (watchdog) over hand-rolled SET NX PX** — safe release and renewal-independent-of-parked-thread are known footguns; build the engine, buy the lock. Watchdog over fixed TTL: lock lifetime tracks holder lifetime — kills the primary zombie source; crash frees ≤30 s.
7. **No DB-buffer fallback for Kafka-down at the gateway** — GitHub's webhook retry is the upstream buffer; a DB buffer recreates cross-store ordering reconstruction (the problem Kafka solves). 503 + redelivery sweep.
8. **No transactional outbox in v1** — gateway has no per-event DB write, so no dual-write exists; worker's DB+GitHub+Kafka triad is handled by ordering (I8/I9) + idempotency. Outbox documented as the pattern *if* a produce-with-DB-write ever appears. (O13 note: the settings path added gateway DB access, but it is human-driven and off the event path — this rationale concerns the webhook flow, which remains DB-free.)
9. **error/defer counter split** — "broken" vs "busy" must not share a threshold, or healthy busy tenants get dead-lettered; error count-bound (5), defer time-bound (2 h).
10. **Approach A retry (single topic, pause-until-due) over tiered topics** — sufficient under near-uniform backoffs at deploy scale; head-of-line limitation named, instrumented (`retry_delay_excess`), with a two-step upgrade path. No speculative topology.
11. **One review comment per reviewed SHA over one updated comment** — composes with per-SHA markers/idempotency unchanged, preserves history, removes merge-into-old-body logic; incremental review makes per-round comments the natural UX.
12. **Blind POST on the happy path over check-before-every-post** — fence/dedup establish sole ownership; existence reads confined to reconciliation/guard paths ⇒ zero happy-path GitHub reads, rate-limit budget preserved.
13. **Skip-not-patch as reconciliation default** — an existing complete comment is adopted, not edited; PATCH reserved for a wrong/stale prior comment (edge of an edge).
14. **Accepted residual (§13.4) over cross-worker post coordination** — closing a sub-millisecond cosmetic, self-healing window would cost permanent hot-path complexity/latency; detect-and-reconcile beats prevent-at-any-cost. Exactly-once vs a no-idempotency-key third party is unattainable; saying so is the senior position.
15. **BYOK over platform-key billing (v1/v2)** — zero LLM COGS, per-tenant provider limits, no billing infrastructure before product-market signal; Marketplace billing vs Stripe deferred to v3 with quota gating at ingestion (a business gate, never a failure mode).

16. **Class-aware claim increment over a separate claims counter** — `error_attempts` must count errors, not claims; defers and breaker-parks re-claim without incrementing it (P1-2). Considered a separate high-ceiling claims counter as the crash backstop; chose class-aware increment (lower complexity; the counter stays semantically honest).
17. **Handler-side retry exhaustion** — exhaustion decided in the outcome handler at attempt 5 (has the exception in hand), not at claim; the claim-side `>5` check is retained only as the poison/hard-crash backstop (C-2).
18. **`round` dimension on review identity** — re-review is a distinct `(sha, round)` unit, so `/rereview` isn't neutralized by the SUCCEEDED dedup branch and the v2 sweep never deletes a legitimate re-review (C-4).
19. **Author authorization on the primary flow** — the §14.4 command permission-gate rationale ("no drive-by burning the owner's budget") swept onto the auto-review path; without it a public repo is an unauthenticated financial DoS on the owner's BYOK key (S-1).
20. **Author-authenticated, HMAC-signed markers** — marker presence is not proof of self-authorship (GitHub allows reviews from any reader); matching filters to the bot user and verifies an HMAC, closing review-suppression/deletion (S-2).
21. **Recovery probes for breakers** — a paused worker makes no calls, so a traffic-driven breaker never closes; a scheduled synthetic probe per provider is what actually recovers the system without a manual restart (P2-1).

---

## Review provenance

v1.1 incorporates a 5-pass adversarial design review (coherence, correctness, operability, security, implementability). 10 blockers + ~18 majors + ~9 minors triaged; **zero rejected on re-derivation; zero required architectural change** — every fix was a field, an ordering, a predicate, a check, or a missing mechanism. Findings clustered into four families, each a transferable lesson: correctness seams (a value two mechanisms read differently), lifecycle holes (state implied but not wired end-to-end), trust boundaries (outsider-controlled content trusted), implementability ambiguities (a decision left to the implementer). The engine (§8) and the v2 fence layering (§13) survived every constructed interleaving. Verdict: READY-WITH-TICKETS.

v1.2 additionally incorporates the fourteen rulings (O1–O14) surfaced during ticket generation — a second fresh-context validation in which a generator decomposed this spec into a ~50-ticket backlog; every design question it was forced to ask was ruled and folded back in here, so the spec remains the single source of truth the backlog references.

*— End of specification. —*
