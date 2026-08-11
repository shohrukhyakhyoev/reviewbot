# **reviewbot — V1 Implementation Backlog**

**Backlog version:** 1.0 (Pass 4 consolidated) · **Source of truth:** `reviewbot-design-spec` **v1.2** (O1–O14 applied) · **Tickets:** 54 across E1–E11 · **Milestone (system runs end-to-end):** E5-9b

## **How to use this document**

- **Workflow per ticket:** pick the next ticket whose dependencies are all DONE → the implementing agent (Claude Code) reads **only** the ticket plus the listed spec sections of spec v1.2 → implements, including all named tests → the human reviews the entire diff → update PROGRESS.md and the ticket state.  
- **Ticket states:** prefix each ticket header line in this file as work proceeds: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked (note why inline).  
- **Sizing:** one ticket \= one focused session \= one component or one coherent group of pipeline steps, including tests. S ≈ small diff, M ≈ typical session, L ≈ full session, review carefully. Pre-authorized in-flight splits exist for E5-3 and E10-0 (named at the ticket) — take them if a session runs long, without renumbering.  
- **Gates (always):** build green · lint clean · JaCoCo ≥ 80% overall · engine classes (§8) \~100% branch · OffsetTracker property-tested over randomized completion orders · PROGRESS.md updated.  
- **Ruling-local copies:** where an O-ruling postdated a ticket, the ruling text is baked into the ticket below; spec v1.2 carries the same content — on any perceived conflict, spec v1.2 wins.  
- **O10 capture-point note:** the RAW variant's `original_key_b64` \+ `original_headers` are captured at **RAW-envelope build time in the worker** (the only moment the original main-topic record exists); the indexer persists them. Intent (capture-before-loss) preserved.

## **Epic plan**

| ID | Epic | Goal | Depends on | Tickets |
| :---- | :---- | :---- | :---- | :---- |
| E2 | Infra & scaffold | Buildable skeleton \+ CI \+ local runtime | none | 5 |
| E1 | Shared contracts | Envelope/codecs, keys, classifier/outcomes, DLQ envelope, port, config | E2-0 | 6 |
| E3 | Webhook gateway | Webhook → durable Kafka event | E1, E2 | 4 |
| E4 | Consumer engine | §8 machinery, integration-proven standalone | E1, E2 | 6 |
| E5 | Core pipeline | `processReview` end-to-end \+ outcome handling; **milestone at E5-9b** | E1, E2, E4 (+E3 for e2e) | 12 |
| E6 | Retry worker | Pause-until-due consumer; one core, both workers | E5 | 3 |
| E7 | DLQ surface | Indexer, admin list/replay/discard, metrics \+ integration | E5 | 3 |
| E8 | Degraded modes | Breakers, dependency gate, health-pause, recovery probes, outage test | E4, E5, E6 | 5 |
| E9 | Features | Settings auth \+ page, `.reviewbot.yml` loader, incremental review | E5, E8 | 4 |
| E10 | Observability | Telemetry completion, dashboards-as-code, alert rules | E5–E8 | 3 |
| E11 | Deploy | Images \+ EKS manifests, AWS foundation, runbook \+ cutover \+ README | all | 3 |

Execution order: E2-0 → E2-1 → E2-2 → E2-3 → E2-4 → E1-0…E1-5 → E3-0…E3-3 → E4-0…E4-5 → E5-0…E5-8, E5-9a, E5-9b, E5-10 → E6-0…E6-2 → E7-0…E7-2 → E8-0…E8-4 → E9-0…E9-3 → E10-0…E10-2 → E11-0…E11-2.

---

# **E2 — Infra & scaffold**

### **\[ \] \[E2-0\] Maven multi-module skeleton**

**Epic:** E2 · **Depends on:** none · **Size:** S

**Spec sections to read first:** §2

**Context:** Single-repo multi-module build: shared types live in one place, a schema change is one atomic commit, and the three deployables build independent images later. This ticket produces the compiling skeleton everything else lands in — no business code.

**Scope — build this:**

- Parent POM: Java 21, Spring Boot 3.x BOM, `dependencyManagement` for the full stack (kafka-clients, Redisson, Resilience4j, Jackson, Flyway, Postgres driver, Testcontainers, WireMock) — versions pinned  
- Modules: `shared` (plain jar), `webhook-gateway`, `review-worker`, `retry-worker` (Spring Boot apps, each with a main class \+ context-load test); `infra/` as a plain directory (not a Maven module)  
- Dependency rule from §2 enforced mechanically (maven-enforcer ban: deployables depend on `shared`, never on each other)  
- Formatter/linter plugin (e.g. Spotless) bound into `verify` so "lint clean" is meaningful from the next ticket on  
- `PROGRESS.md` at repo root, `.gitignore`

**Out of scope:** any domain types (E1), compose (E2-2), CI (E2-1), Dockerfiles (E11)

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] `mvn clean verify` green from the root on a clean checkout  
- [ ] A test dependency from `review-worker` on `webhook-gateway` fails the build (enforcer verified once, reverted)  
- [ ] **Tests:** one context-load test per deployable module (no external services required)  
- [ ] build green, lint clean, PROGRESS.md created

**Known traps:** none

---

### **\[ \] \[E2-1\] CI pipeline**

**Epic:** E2 · **Depends on:** E2-0 · **Size:** S

**Spec sections to read first:** §18 (Gate paragraph), §2 (module list)

**Context:** From here on, every ticket's diff is validated by CI. The §18 gate is JaCoCo ≥ 80% overall with engine classes (§8) at \~100% branch — the engine rule is configured now and activates when those classes exist.

**Scope — build this:**

- GitHub Actions workflow on PR \+ main: `mvn verify` (Testcontainers-capable runner), lint/format check, JaCoCo aggregate report  
- Coverage gates: global ≥ 80% line; a per-class \~100% branch rule scoped to an engine package pattern (convention established here, e.g. `**/engine/**` in `review-worker` — E4 must place classes there; matches nothing until then)  
- Exclude Spring Boot `*Application` mains from coverage; Maven dependency caching

**Out of scope:** deploy/publish jobs, image builds (E11), compose-based jobs (E2-4 adds one)

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Pipeline green on main  
- [ ] Demonstrably fails on each of: a failing test, a lint violation, coverage below gate (verified once via throwaway branch, reverted)  
- [ ] **Tests:** the pipeline run itself \+ the three negative verifications  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** none

---

### **\[ \] \[E2-2\] Local compose stack**

**Epic:** E2 · **Depends on:** E2-0 · **Size:** M

**Spec sections to read first:** §19 (item 15), §4.4 (local dev override paragraph), §2 (infra layout)

**Context:** The manual local runtime: single-broker Kafka (KRaft), Postgres 16, Redis 7, WireMock standing in for GitHub and Anthropic. It deliberately diverges from production replication settings — documented, not fixed.

**Scope — build this:**

- `infra/docker-compose.yml`: Kafka (KRaft single broker, RF=1 / `min.insync.replicas=1`, **`auto.create.topics.enable=false`** so E2-4's provisioning is the only topic source), Postgres 16, Redis 7, WireMock (empty mappings dir; per-test stubs stay in Testcontainers per §18)  
- Healthchecks on all services; named volumes for Postgres/Kafka  
- `infra/verify-stack.sh` smoke script: Kafka reachable (list topics), Postgres `SELECT 1`, Redis `PING`, WireMock admin endpoint  
- `infra/README.md`: up/down commands, the RF=1/ISR=1 divergence note, smee/ngrok pointer (§19.15)

**Out of scope:** topic creation (E2-4), Flyway service (E2-3), k8s (E11), WireMock stub content

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] `docker compose up -d` reaches all-healthy from cold on a clean machine  
- [ ] **Tests:** `verify-stack.sh` exits 0 against the running stack; non-zero with the stack down  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §4.4/§19.15 — local RF=1/ISR=1 is intentional; don't chase phantom `NOT_ENOUGH_REPLICAS` locally, don't "fix" compose to RF=3

---

### **\[ \] \[E2-3\] Flyway V1 migration**

**Epic:** E2 · **Depends on:** E2-0, E2-2 · **Size:** S

**Spec sections to read first:** §5.1 (entire, incl. the status-semantics block after the DDL)

**Context:** Postgres is the durable source of truth; this ships the complete V1 schema — `reviews` (incl. v1 `round` and the v2-ready `fence`, both in the spec DDL), `installations`, `dlq_entries` — verbatim. Status semantics are code-enforced, so the DDL is taken as written.

**Scope — build this:**

- `infra/db/migration/V1__init.sql` per §5.1 verbatim: three tables, `UNIQUE (installation_id, repo_full_name, pr_number, head_sha, round)`, `UNIQUE (dlq_partition, dlq_offset)`, indexes `idx_reviews_rate`, partial `idx_reviews_last`, `idx_dlq_status`, `idx_dlq_tenant`  
- Flyway service in compose (official image, mounts the migration dir)  
- Schema smoke test in `shared` test scope: Testcontainers Postgres \+ programmatic Flyway pointed at `infra/db/migration` (apps do **not** self-migrate — O1; production application \= k8s migration Job, E11)

**Out of scope:** CHECK constraints/enum types (not in spec DDL), v2 tables, seed data

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Migration applies cleanly on an empty database; re-run is a no-op  
- [ ] **Tests:** smoke test asserts via `information_schema`/`pg_indexes`: all tables, both UNIQUEs, all four indexes incl. the `WHERE status = 'SUCCEEDED'` partial, `round` default 1, `fence` default 0  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** none

---

### **\[ \] \[E2-4\] Topic provisioning**

**Epic:** E2 · **Depends on:** E2-2 · **Size:** S

**Spec sections to read first:** §4.1, §4.2 (`min.insync.replicas` row — topic-level), §4.4

**Context:** Three topics with exact partition counts, retention, and cleanup policy; `min.insync.replicas=2` at topic level is what gives `acks=all` its durability guarantee in production. Partition counts are near-immutable (§4.4), so provisioning is explicit and idempotent.

**Scope — build this:**

- Idempotent script in `infra/kafka/` (kafka-topics.sh based): `pr.review.requested` 6 / `pr.review.retry` 3 / `pr.review.dlq` 3 partitions; retention 7d/3d/30d; `cleanup.policy=delete` on all; create-if-absent, verify-never-alter on existing  
- Environment parameterization: local RF=1/ISR=1, prod RF=3/ISR=2 — prod values rendered and asserted even where a single broker can't host them  
- Compose init service invoking the script after Kafka healthy  
- CI job: compose Kafka up → run script twice → scripted `--describe` assertions (counts, retention, cleanup; second run changed nothing)  
- Script header note: topic names/partition counts also exist as `shared` constants (E1-5); script is authoritative for infra — keep in sync

**Out of scope:** MSK provisioning (E11), §4.4 migration tooling (documented runbook only), Testcontainers topic fixtures (tests self-provision)

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Fresh stack \+ script ⇒ all three topics with exact §4.1 configs; second run is a verified no-op  
- [ ] Script fails loudly on an existing topic with mismatched partition count — never "fixes" it  
- [ ] **Tests:** the CI compose job with `--describe` assertions incl. the idempotence re-run  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §4.4 — `--alter --partitions` is forbidden on keyed topics (`hash(key) % N` reshuffles, silently breaking per-PR ordering); the script refuses rather than alters. Local RF divergence per §4.4/§19.15.

---

# **E1 — Shared contracts**

### **\[ \] \[E1-0\] Event envelope, validation & header codecs**

**Epic:** E1 · **Depends on:** E2-0 · **Size:** M

**Spec sections to read first:** §3.1, §3.2, §3.3, §19 (items 5, 13\)

**Context:** The one wire contract shared by gateway and both workers — the monorepo replaces a schema registry, so this class plus its evolution rules *is* the contract enforcement. Header codecs live here because a codec mismatch between workers is a silent interop bug (§19.13).

**Scope — build this:**

- `ReviewRequestedEnvelope` per §3.1: all fields; `type` enum with `REVIEW_REQUESTED` only (v1); `force`/`replay` default false  
- Jackson: `FAIL_ON_UNKNOWN_PROPERTIES=false` on all consumers; explicit post-bind **per-type** validation (O2, ruled): all §3.1 fields required except defaulted `force`/`replay`; `schema_version` must equal 1 (else validation failure); `head_sha` required for `REVIEW_REQUESTED` (optional for v1.5 command types); failure surfaces as a typed exception (the `POISON_DESERIALIZATION` input — cross-ref E1-2)  
- Serde helpers (JSON bytes ↔ envelope) used by producer and consumers  
- Header codecs per §3.2 in one place: `not_before`, `first_received_at`, `first_deferred_at` \= epoch-ms strings; `error_attempts`, `defer_attempts` \= decimal-int strings; `failure_class`, `trace_id` \= strings. Encode \+ decode both directions

**Out of scope:** v1.5 command types, Kafka client wiring (E3/E4/E6), classifier logic (E1-2)

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Envelope round-trips; unknown extra fields ignored on read (tolerant reader)  
- [ ] Missing any required field ⇒ typed validation failure; `schema_version ≠ 1` rejected; `head_sha` absence rejected for `REVIEW_REQUESTED`  
- [ ] **Tests:** contract tests per §18 — round-trip serde; tolerant-reader; one missing-field case per required field; per-type validation matrix; codec round-trip for every header incl. epoch 0 and far-future  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.13 — codecs used by both workers from `shared`, never re-implemented; §19.5 — tolerant-reader \+ explicit validation, not annotation-required

---

### **\[ \] \[E1-1\] PR key \+ Redis key builders**

**Epic:** E1 · **Depends on:** E2-0 · **Size:** S

**Spec sections to read first:** §3.1 (Kafka message key line), §5.2 (full table \+ skip-namespace note), §19 (item 14\)

**Context:** Single source for every key string: the Kafka partition key guaranteeing per-PR ordering, and the four Redis key families. Gateway and workers build keys only through this.

**Scope — build this:**

- `PrKey` value type `{installation_id, repo_full_name, pr_number}`: render to `"{inst}:{repo}:{pr}"` (all three topics), parse from a `ConsumerRecord` key (first/last colon — `repo_full_name` contains `/`, never `:`)  
- Redis key builders per §5.2: `delivery:{guid}` · `dedup:{inst}:{repo}:{pr}:{sha}:{round}` · `lock:review:{inst}:{repo}:{pr}` (no SHA — deliberate) · `ghcfg:{inst}:{repo}`  
- Co-located TTL constants: delivery 24 h, dedup 7 d, lock 30 s, ghcfg 5 min (§5.2/§12)

**Out of scope:** Redis client calls; any `skip:` namespace builder (don't build speculatively)

**Invariants that apply:** none directly (key format underpins I2's cross-instance half)

**Acceptance criteria:**

- [ ] Golden-string tests pin every key format, incl. `round` in the dedup key (C-4) and no SHA in the lock key  
- [ ] **Tests:** golden formats for all five builders; `PrKey` parse↔render round-trip incl. repo names with dots/hyphens/underscores; TTL constants match §5.2  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.14 — `dedup:` means reviewed-and-posted only; deliberately no builder for a skipped/superseded marker

---

### **\[ \] \[E1-2\] Failure classification \+ outcome types**

**Epic:** E1 · **Depends on:** E2-0 · **Size:** M

**Spec sections to read first:** §10.1 (table \+ ambiguous-403 paragraph), §7.2 (outcome enum \+ handler consumption), §19 (item 9\)

**Context:** The `RetryDecision` function is a real component in `shared` (§10.1): every failure maps to a class, a kind, and a route — the single vocabulary pipeline, retry worker, and DLQ all speak. The typed exception hierarchy is also E8's breaker seam (`ignoreExceptions: [TerminalReviewException]`).

**Scope — build this:**

- `FailureClass` model covering every §10.1 string incl. `EXHAUSTED_{class}` and `COULD_NOT_SCHEDULE`; wire representation is the string (headers §3.2, envelopes §10.2)  
- Kind \+ route mapping exactly per §10.1: terminal → DLQ · skip → no DLQ/no retry · transient → error schedule · infra (Postgres/Redis op failure, P1-4) → `BREAKER_PARKED(dep)`, no counters · defer → defer schedule · breaker remnant → cooldown, no counters  
- Outcome type per §7.2: `SUCCESS | SKIP(class?) | DEFER(class, notBefore?) | TRANSIENT(class, exception) | TERMINAL(class, exception) | BREAKER_PARKED(dep)` — each variant carries what its handler row consumes  
- Exception hierarchy: `TerminalReviewException` (E8's `ignoreExceptions`), transient/defer-typed provider exceptions  
- Classifier over a `ProviderError` abstraction (status, headers, body snippet — no HTTP client types): 429/rate-limit headers ⇒ DEFER with `not_before` from `Retry-After`/reset; **ambiguous 403 ⇒ inspect headers/body — rate-limit ⇒ defer, else permission ⇒ terminal**; 5xx/timeout ⇒ transient; 404 ⇒ `PR_NOT_FOUND`

**Out of scope:** backoff schedule values (E1-5), outcome handlers (E5), breaker wiring (E8)

**Invariants that apply:** I5 — every record reaches exactly one terminal outcome; this ticket defines the closed vocabulary that makes that checkable

**Acceptance criteria:**

- [ ] Every §10.1 row classifies to its specified class/kind/route  
- [ ] **Tests:** table-driven unit test, one case per §10.1 row (§18 requirement); ambiguous-403 both ways; `Retry-After` seconds and reset-epoch parsing; `EXHAUSTED_{class}` wire-string derivation  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.9 — terminal classes must be breaker-invisible: `TerminalReviewException` exists so a 404 wave never trips a provider breaker

---

### **\[ \] \[E1-3\] DLQ envelope builder**

**Epic:** E1 · **Depends on:** E1-0, E1-2 · **Size:** S

**Spec sections to read first:** §10.2 (both variants \+ S-4 hygiene; v1.2 carries the O10 RAW fields), §3.1 (for `original_event` verbatim)

**Context:** Every `TERMINAL` outcome (E5) and the retry worker's exhaustion path (E6) build this envelope in `shared`. Its purpose is human diagnosis plus lossless replay — so `original_event` must be the *original bytes* (the tolerant reader drops unknown fields a re-serialized POJO would lose), and the RAW variant must capture everything replay needs before the original ages out.

**Scope — build this:**

- Standard envelope per §10.2: full `metadata` block \+ `original_event` as raw JSON (bytes/`JsonNode`), never a re-serialized object  
- Poison RAW variant (D-2 \+ O10): `{kind: RAW, schema_version: 1, raw_payload_b64, original_key_b64 (nullable), original_headers (map name → base64 value), origin, exception, dlq_at}` — key/headers taken from the poison `ConsumerRecord` at build time (the worker holds it; the indexer only persists); DLQ record key \= sentinel `poison:{origin_partition}:{origin_offset}`  
- S-4 hygiene: `body_snippet` capped at **2048 characters, truncated with literal `…[truncated]`** (O3, a §12 constant) and annotated "may echo customer input"; builder never accepts diff or prompt content  
- Builder output \= complete DLQ record: key (PrKey string or poison sentinel), headers (`failure_class`, `installation_id` mirrored per §10.2), JSON body

**Out of scope:** producing to Kafka (E5/E6), indexer \+ `dlq_entries` (E7)

**Invariants that apply:** none (I9 governs the producing side, in E5/E6)

**Acceptance criteria:**

- [ ] Golden-JSON tests for both variants match §10.2 field-for-field, incl. the O10 RAW fields  
- [ ] An unknown field planted in the incoming event survives into `original_event` byte-preserved  
- [ ] **Tests:** golden structure both variants; verbatim-preservation; snippet capped at 2048 \+ suffix \+ annotation; RAW key/headers round-trip (b64) incl. null-key case; poison sentinel key format; both headers mirrored via E1-0 codecs  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.13 — header values via the E1-0 codecs, no ad-hoc `String.valueOf`

---

### **\[ \] \[E1-4\] ReviewGenerator port \+ domain types**

**Epic:** E1 · **Depends on:** E2-0 · **Size:** S

**Spec sections to read first:** §14.2, §7.1 step 10 (findings structure), step 11 (severity consumption)

**Context:** The hexagonal seam: `ReviewGenerator` in `shared`, one adapter later (E5), a second provider \= one class, zero pipeline changes. This ticket freezes the port signature and finding vocabulary; payload types stay minimal and grow additively.

**Scope — build this:**

- `interface ReviewGenerator { Review generate(Diff d, ReviewContext c, ModelRef m); }` exactly per §14.2  
- `Review` \= findings `{file, summary, severity, detail}`; `Severity` enum `CRITICAL | MAJOR | MINOR | NIT` with explicit ordering \+ `atOrAbove(min)` helper  
- `Diff` (files: path, patch, status) and `ReviewContext` (installation, repo, pr, base/head sha, round) as minimal records — Javadoc notes E5/E9 may extend fields additively; port signature frozen  
- `ModelRef` wrapper; `StaticReviewGenerator` stub in test fixtures for downstream tests without Anthropic

**Out of scope:** the Anthropic adapter, BYOK, per-installation factory (all E5)

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Port compiles as the only LLM-facing surface in `shared`; no provider types leak  
- [ ] **Tests:** severity ordering \+ `atOrAbove` matrix (all 16 pairs); stub returns deterministic findings usable by later integration tests  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** none

---

### **\[ \] \[E1-5\] Config models \+ constants**

**Epic:** E1 · **Depends on:** E2-0, E1-4 · **Size:** M

**Spec sections to read first:** §12 (entire table, incl. v1.2's O3/O7/O8/O11 rows), §4.1, §7.1 steps 2.5 \+ 7, §6.1 item 2

**Context:** Every §12 number becomes a typed, defaulted config property — one source all modules read, so a value can never drift. Also the `.reviewbot.yml` *model and lenient parser* (pure function); the fetch/cache loader is E9.

**Scope — build this:**

- Typed config (`@ConfigurationProperties`) with §12 defaults verbatim: error backoff \[10 s, 1 m, 5 m, 15 m\] · defer backoffs (LOCK\_HELD 30 s flat; RATE\_LIMIT 1 m→2 m→5 m capped) · defer SLA 2 h · breaker cooldown 60 s · rate cap 5 · in-flight 200 / resume 100 / `max.poll.records` 100 · timeouts (GitHub 2 s/10 s, Anthropic 120 s, PG 5 s, Redis 2 s) · lock wait 2 s · gateway produce deadline 5 s · probe interval 15 s · health cadence 5 s (O11) · stale-PROCESSING 10 min · shutdown grace 30 s · Redis TTLs (cross-check E1-1 — one definition) · `body_snippet` cap 2048 (O3) · author-cache TTL 5 min \+ bound (O7) · per-model token-budget map default 150 000 \+ 0.9 guard factor (O8)  
- Topic constants: names \+ partition counts per §4.1, sync-comment cross-ref to E2-4  
- `RepoConfig` model: `model` (optional override), `min_severity` (E1-4 `Severity`), `review_authors ∈ {write, contributors, all}` default `write`; parse per §7.7 — missing ⇒ defaults, any parse/validation error ⇒ **all** defaults \+ log  
- Default model constant `claude-sonnet-4-6` (§5.1)

**Out of scope:** `.reviewbot.yml` fetching/caching (E9); Kafka client wiring (E3/E4/E6)

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Golden test asserts every default equals its §12 value — a drifted number fails the build  
- [ ] **Tests:** defaults-vs-§12 golden; `RepoConfig` parse matrix (valid; unknown keys ignored; malformed ⇒ defaults; invalid enum ⇒ defaults \+ logged); topic constants match §4.1  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** none

---

# **E3 — Webhook gateway**

### **\[ \] \[E3-0\] HMAC verification filter**

**Epic:** E3 · **Depends on:** E2-0 · **Size:** S

**Spec sections to read first:** §6.1 (item 1), §19 (item 6\)

**Context:** The trust boundary: nothing past this filter is an unverified payload. Verification runs before any parsing, over the exact raw request bytes. (O13: this filter scopes to `/webhook` only — `/settings/**` gets its own auth in E9-0.)

**Scope — build this:**

- Servlet filter on `POST /webhook`: HMAC-SHA256 of the **raw body bytes** against the webhook secret; compare to `X-Hub-Signature-256` via `MessageDigest.isEqual`; mismatch/missing → `401`, stop  
- Raw-body caching wrapper so bytes are HMAC'd and then parsed downstream (re-serialized JSON is never the HMAC input)  
- Webhook secret from config/env; never logged, excluded from actuator output  
- Filter ordering pinned: HMAC strictly before any payload parsing; path-scoped to `/webhook`

**Out of scope:** event filtering (E3-1), any response beyond 401/pass-through

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Valid signature passes; invalid, tampered-body, missing-header all 401 without reaching any handler  
- [ ] **Tests:** unit — known HMAC vectors (valid/tampered/missing/wrong-length); multi-byte UTF-8 payload verifies (raw-bytes proof); handler-spy proving unverified requests never reach parsing; `MessageDigest.isEqual` asserted  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.6 — constant-time compare; the subtle variant is hashing a re-parsed body instead of raw bytes

---

### **\[ \] \[E3-1\] Event filter \+ envelope build**

**Epic:** E3 · **Depends on:** E3-0, E1-0 · **Size:** M

**Spec sections to read first:** §6.1 (items 2–3), §3.1

**Context:** Decides which GitHub events become work and builds the §3.1 envelope. Deliberately-ignored events get `200` so GitHub never retries a no-op. Draft handling per P4-2: skips are config-gated (O4: gateway app-level, global default false), and `ready_for_review` must never be swallowed by the draft check.

**Scope — build this:**

- Accept `pull_request` with action ∈ {`opened`, `synchronize`, `reopened`, `ready_for_review`}; everything else → `200` ack no-op \+ metric  
- Draft logic, order pinned: `ready_for_review` → accept unconditionally (treated as `opened`); otherwise `draft == true` and `review_drafts=false` (O4) → `200` ack no-op  
- Envelope per §3.1: `event_id = UUID`, `delivery_id = X-GitHub-Delivery`, `type = REVIEW_REQUESTED`, `occurred_at =` PR `updated_at`, identity fields, `head_sha = payload.pull_request.head.sha`, `force=false`, `replay=false`, `schema_version=1`  
- Accepted-type payload missing a required extraction field → `200` ack \+ warn log \+ metric (identical bytes re-fail identically; GitHub must not retry what cannot succeed)

**Out of scope:** v1.5 comment events and the §19.8 bot-loop guard (comment-event-only), dedup/produce (E3-2/E3-3), Kafka headers (E3-3)

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Action matrix exact, incl. draft × `ready_for_review` interaction  
- [ ] **Tests:** unit — all four accepted actions produce a golden envelope from realistic payload fixtures; ignored events (`push`, `ping`, `pull_request/closed`) acked no-op; draft skipped under default; draft reviewed with `review_drafts=true`; `ready_for_review` accepted despite draft; malformed accepted-type payload → 200 \+ metric  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** none

---

### **\[ \] \[E3-2\] Delivery dedup component**

**Epic:** E3 · **Depends on:** E1-1 · **Size:** S

**Spec sections to read first:** §6.1 (items 4a, 4c), §5.2 (`delivery:` row)

**Context:** Redis-backed webhook-delivery dedup for GitHub's retry window. The marker means "durably enqueued," not "seen" — so this component exposes only the two halves (`check`, `markEnqueued`) and E3-3 wires them around the produce in the C-1 order. Redis is a cache here: unreachable ⇒ fail open.

**Scope — build this:**

- Redisson client wiring in the gateway (2 s op timeout §12)  
- `check(deliveryId)`: `EXISTS delivery:{guid}` → hit \= already enqueued; **Redis unreachable → fail open (proceed as miss)** \+ metric  
- `markEnqueued(deliveryId)`: `SET NX EX 86400`, best-effort — failure swallowed with log/metric  
- Metrics: dedup hits, fail-open occurrences

**Out of scope:** call ordering relative to produce (E3-3 owns C-1), other Redis keys

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Hit/miss/NX semantics and 24 h TTL exact; fail-open on both operations  
- [ ] **Tests:** Testcontainers Redis — miss→mark→hit; second `markEnqueued` no-op (NX); TTL ≈ 86400; Redis stopped → `check` proceeds as miss \+ metric, `markEnqueued` swallows \+ logs; 2 s timeout budget honored  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** C-1 (§6.1) — never couple check-and-mark into one call; the split API is what makes E3-3's ordering enforceable

---

### **\[ \] \[E3-3\] Producer \+ endpoint orchestration**

**Epic:** E3 · **Depends on:** E3-1, E3-2, E1-5 · **Size:** M

**Spec sections to read first:** §6.1 (item 4 complete), §4.2, §12 (gateway produce-await row), §3.2 (headers set on produce)

**Context:** Assembles the gateway: verified request → filter → dedup check → produce → mark → respond, in the C-1 order that makes a crash at any point safe. The 5 s bounded await (P4-1) keeps the gateway under GitHub's 10 s timeout; a late-completing produce after a 503 is an accepted, downstream-absorbed duplicate.

**Scope — build this:**

- `KafkaProducer` per §4.2 verbatim: `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`, `delivery.timeout.ms=120000`, `compression.type=lz4`; topic from E1-5  
- Orchestration, order fixed (C-1): `check()` hit → `200` stop · miss/fail-open → produce (key \= E1-1 render; value \= E1-0 serde; headers via codecs: `first_received_at = now`, `trace_id`) → await ack **bounded 5 s** → success: `markEnqueued()` then `200` · failure/timeout: **`503`, marker NOT set**, no late-callback marker  
- Gateway metrics per §15: webhook latency, non-2xx count

**Out of scope:** DB access (none exists — decision log \#8), redelivery sweep (E11 runbook), k8s (E11)

**Invariants that apply:** none of I1–I10; C-1 is this ticket's load-bearing ordering

**Acceptance criteria:**

- [ ] Signed webhook → one record with correct key/envelope/headers; `200` well under a second locally  
- [ ] Duplicate delivery id → `200`, no second record  
- [ ] Kafka unavailable → `503` within \~5 s, marker absent; after Kafka returns, the same delivery id succeeds  
- [ ] **Tests:** integration (Testcontainers Kafka \+ Redis, full HTTP) — happy path with envelope/key/header assertions; duplicate-delivery; Redis-down fail-open still produces; Kafka-stopped → 503 ≈5 s \+ marker-absent \+ retry-succeeds-later; unit — producer config golden pinning every §4.2 value  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** C-1 — marker only after broker ack; setting it before (or on the 503 path) silently loses events. §19.13 — headers via E1-0 codecs only.

---

# **E4 — Consumer engine**

### **\[ \] \[E4-0\] OffsetTracker**

**Epic:** E4 · **Depends on:** E2-0 · **Size:** M

**Spec sections to read first:** §8.4 (entire, incl. I3 paragraph; v1.2 carries the O5 additions), §8 intro (shared objects \+ sharing map), §18 (OffsetTracker bullet), §19 (items 1, 2\)

**Context:** The delicate \~50 lines: contiguous-watermark accounting that makes out-of-order completion compatible with Kafka's single-pointer commit. Committing past a gap is the one unrecoverable failure in the whole design — property tests and \~100% branch coverage apply (engine package; the E2-1 gate activates here).

**Scope — build this:**

- Class per §8.4 verbatim: per-partition `{watermark, islands NavigableSet}`, `synchronized(p)` around µs of pure computation, adjacent-island absorption, `inFlight.release()` as the one door (I4; semaphore constructor-injected)  
- `register(tp, baselineOffset)` (O5): initializes/resets partition state, `watermark = baseline − 1`; called by the poller on assignment with `consumer.position(tp)`; re-registration discards stale state  
- **O5 refinement:** `markComplete(tp, off)` on an unregistered or revoked partition is an offset-accounting no-op that **still releases the permit** (I4 — zombie completions must not leak permits) and never surfaces in `committable()`  
- `committable()`: per-partition snapshot of watermarks advanced since registration/last snapshot (dirty-tracked — no redundant baseline re-commits); `committable(parts)` overload for revocation (E4-4)  
- Returns **watermarks** — the `+1` is applied at the commit site only (§19.2)

**Out of scope:** Kafka client types beyond `TopicPartition`, commit calls (E4-3), revocation policy (E4-4)

**Invariants that apply:** I3 — commit only the highest N with every offset ≤ N terminal · I4 — permits: acquire@dispatch, release@markComplete, conserved always (incl. the zombie no-op)

**Acceptance criteria:**

- [ ] Out-of-order completion parks in islands; contiguous completion advances and absorbs transitively  
- [ ] Complete-after-revoke/unregister releases the permit and never appears in `committable()` (the O5 unit test, mandatory)  
- [ ] **Tests:** property tests (jqwik or seeded-random) per §18 — random offset sets × completion orders ⇒ snapshot watermark never exceeds the contiguous prefix, equals it after full completion, permits conserved (I3, I4); unit — multi-island absorption chain, gap-park, register-reset discards stale state, dirty-snapshot semantics, `committable(parts)` filtering, zombie no-op, multithreaded stress. \~100% branch  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.1 — `synchronized` is safe *because* the block is pure computation; never add I/O inside. §19.2 — watermark+1 belongs to the committer, not the tracker.

---

### **\[ \] \[E4-1\] Coalescer (pipeline step 0\)**

**Epic:** E4 · **Depends on:** E1-0, E1-2 · **Size:** S

**Spec sections to read first:** §7.1 (step 0), §8.3 (call site \+ plan shape), §8.2 (why routing never "cleans" queues), §3.3 (rule 4\)

**Context:** Pure function from drained batch to plan: v1 `REVIEW_REQUESTED` events for one PR collapse to the newest (highest offset — offset order *is* arrival order); superseded become immediately-terminal SKIPs with no DB row. Deserialization happens here (the poller never inspects payloads); a record failing §3.3 validation becomes an isolated poison outcome.

**Scope — build this:**

- `coalesce(List<ConsumerRecord>) → Plan {poison: [(record, TERMINAL(POISON_DESERIALIZATION)+exception)], superseded: [→ SKIP(SUPERSEDED)], jobs: [(record, envelope)]}` — every input lands in exactly one slot (the I5 precondition); the poison record itself is carried for E1-3's RAW capture (key/headers)  
- Deserialize \+ validate via E1-0 (per-type per O2); failures → poison, excluded from collapse  
- Collapse valid `REVIEW_REQUESTED` to highest offset; v1 output ≤1 job. Partition-by-`type` shape per §7.0 so v1.5 command survival is additive — build no command handling  
- Superseded semantics documented on the type: terminal SKIP, `markComplete`, **no DB row, no dedup marker** (P1-1)

**Out of scope:** any I/O, queue/drainer mechanics (E4-2), command types

**Invariants that apply:** I5 — the plan's exhaustive partition is where exactly-one-outcome starts

**Acceptance criteria:**

- [ ] Deterministic; jobs chosen by offset, never timestamp  
- [ ] **Tests:** unit — single record passes; N same-PR → newest is the job, N−1 superseded; poison mixed in → isolated, remainder coalesces; poison-only; empty; randomized batches ⇒ slot counts sum to input and every record appears exactly once  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §8.2's warning shapes this design — coalescing is drainer-side domain judgment; never move any of it into routing

---

### **\[ \] \[E4-2\] Dispatcher \+ drainer \+ retirement**

**Epic:** E4 · **Depends on:** E4-0, E4-1, E1-1 · **Size:** L

**Spec sections to read first:** §8.2, §8.3 (entire), §8 intro (sharing map), §7 intro (outermost catch-all / I5), §18 (dispatcher/drainer bullet), §19 (item 1\)

**Context:** The per-key serialization core: a passive dispatcher routes by PR key into ephemeral queues; one virtual-thread drainer per PR-with-pending-work drains, coalesces, executes, completes. The sharing map is law — the dispatcher touches neither semaphore nor tracker. E5's pipeline plugs in through two seams built here; E4 tests run against stubs.

**Scope — build this:**

- `PrQueue`: thread-safe `add` \+ `drainAll` (everything currently queued, arrival order)  
- Dispatcher per §8.2 verbatim (`ConcurrentHashMap.compute`, new-queue-⇒-submit, else append); `markClosed(parts)` / `reopen(parts)` for I7 — route() on a closed partition is unreachable by construction (poller filters, E4-3): assert \+ metric  
- `tryRetire(key)`: emptiness re-checked **inside** `map.compute` (I10) — a racing record lands before retirement (drainer loops) or after (fresh drainer spawned)  
- Drainer per §8.3: loop drainAll → empty ⇒ tryRetire-or-continue → coalesce (E4-1) → complete poison \+ superseded → execute jobs via injected `JobExecutor` seam → complete via injected `CompletionHandler` seam. E4 stub handler \= `tracker.markComplete` only; E5 replaces with the full §7.2 handler (whose contract ends in `markComplete`)  
- Outermost catch-all (§7 intro): executor throw ⇒ classify (E1-2) → route to handler; handler throw ⇒ last-resort direct `markComplete` \+ error metric — no path misses its exactly-one completion (I5) or leaks its permit (I4)  
- Executor: `Executors.newVirtualThreadPerTaskExecutor()`, create-per-task-and-discard (pooling virtual threads is an anti-pattern, §8.3)

**Out of scope:** the real `JobExecutor`/`CompletionHandler` (E5), semaphore acquisition (poller-side, E4-3), Kafka client usage

**Invariants that apply:** I2 — per-PR serial within instance, structurally · I4 — permit conservation (release lives in the tracker; never bypass) · I5 — exactly one outcome even on exceptions · I10 — retirement handshake, no lost-wakeup window

**Acceptance criteria:**

- [ ] For any interleaving of `route()` and drainer progress: at most one live drainer per key, and a key with pending work always has one  
- [ ] **Tests:** per §18 — concurrent `route()` storm on one key ⇒ exactly one drainer (I2, spawn-counting); instrumented retirement race (record injected during `tryRetire` via latch) ⇒ never stranded (I10); throwing `JobExecutor` and throwing `CompletionHandler` ⇒ completion exactly-once, permits conserved (I4/I5); arrival order preserved into coalescer; multi-key stress (N router threads × M keys, random stub latencies) ⇒ all complete, per-key max-concurrency \== 1 (overlap detector), map empty at quiescence. \~100% branch  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.1 — drainers block (by design) on virtual threads; any lock that might block must be `ReentrantLock`, never `synchronized` — pinning risk is real here, unlike the tracker

---

### **\[ \] \[E4-3\] Poller**

**Epic:** E4 · **Depends on:** E4-0, E4-2, E1-5 · **Size:** M

**Spec sections to read first:** §8.1 (loop verbatim \+ Owns/Never paragraph), §4.3, §12 (poll timeout / caps / session rows), §19 (item 2\)

**Context:** The single platform thread that owns the `KafkaConsumer` (I1) — intake, backpressure via the composed pause predicate, and watermark commits. Cycle time stays in milliseconds forever, which is what makes `max.poll.interval.ms` structurally a non-issue. Also wires the engine into the review-worker app with a logging stub executor so the worker consumes end-to-end before E5.

**Scope — build this:**

- `KafkaConsumer` per §4.3 verbatim (group `review-worker`, manual commits, `CooperativeStickyAssignor`, 45 s/3 s, `max.poll.records=100`, `earliest`); values from E1-5  
- Loop per §8.1 verbatim: `wantPause = dependencyPause || permits < MAX_POLL_RECORDS`; `canResume = !dependencyPause && permits >= RESUME_THRESHOLD` (CF-2 — one unit, no inversion); `pause/resume(assigned)`; `poll(200 ms)`; per record `inFlight.acquire()` → `dispatcher.route(r)`; commit pass `tracker.committable()` → `commitAsync(wm + 1, logOnError)`  
- `volatile dependencyPause` flag \+ setter (E8 drives it); default false; `pause_state` gauge (`reason=backpressure` now, E8 extends)  
- Intake filter: a polled record whose partition is not currently assigned (post-revocation fetch-buffer edge) → `tracker.markComplete` directly, never routed (poller↔tracker is legal; keeps the dispatcher's closed-assert unreachable)  
- Minimal internal rebalance listener for this ticket only: `onPartitionsAssigned` → `tracker.register(tp, consumer.position(tp))` (O5) — E4-4 replaces with the full I7 protocol  
- App wiring: engine assembled in review-worker (Spring), stub logging `JobExecutor`/`CompletionHandler` beans marked for E5 replacement

**Out of scope:** revocation protocol \+ shutdown (E4-4), commit filtering to assigned (E4-4 — revocation semantics), breaker wiring (E8)

**Invariants that apply:** I1 — one thread owns the consumer; only sanctioned cross-thread interaction is `wakeup()` (E4-4) · I6 — pause/resume hysteresis exactly as §8.1

**Acceptance criteria:**

- [ ] Permits exhausted below threshold ⇒ `pause` called once (not per-cycle); resume once when clear; `dependencyPause=true` alone pauses and blocks resume  
- [ ] Committed offset for a partition is always tracker watermark \+ 1  
- [ ] **Tests:** unit vs `MockConsumer` — pause/resume transition matrix incl. hysteresis; dependencyPause interplay; commit math golden (wm+1); unassigned-record intake filter completes without routing; register-on-assign called with position; acquire-before-route ordering. \~100% branch on the loop class  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.2 — `wm + 1` at exactly this call site. P1-3/CF-1 (§8.1): `max.poll.records` ≤ in-flight cap is what makes `acquire()` never truly block — don't "simplify" the predicate or constants.

---

### **\[ \] \[E4-4\] Rebalance listener \+ shutdown**

**Epic:** E4 · **Depends on:** E4-3 · **Size:** M

**Spec sections to read first:** §8.1 (rebalance-listener paragraph, verbatim; v1.2 carries the O5 unregister addition), §12 (shutdown-grace row), §19 (item 2\)

**Context:** The I7 revocation protocol and graceful shutdown. Revoked partitions' in-flight drainers run on as *tolerated zombies*: completions recorded locally, never committed — the new owner re-receives and the idempotency stack absorbs it. "Never committed" is design; the coordinator's stale-generation rejection is backstop only.

**Scope — build this:**

- Full `ConsumerRebalanceListener` (replaces E4-3's minimal one; callbacks on the poller thread inside `poll()` — I1 preserved): `onPartitionsRevoked(parts)` → `dispatcher.markClosed(parts)` → grace \~3 s → `commitSync(tracker.committable(parts))` at wm+1 → **unregister those partitions in the tracker (O5 — drop state so stale islands never contaminate a future re-registration)** → return · `onPartitionsAssigned(parts)` → `tracker.register(tp, position)` → `dispatcher.reopen(parts)`  
- Commit-pass restriction in the poller: async commits filtered to currently-assigned partitions — zombie advances never committed by us  
- Shutdown (SIGTERM): `running=false` \+ `consumer.wakeup()` (the one legal cross-thread consumer call) → stop intake → drain grace 30 s (await permits back to cap, bounded) → final `commitSync(committable())` → close consumer, shut executor; grace is a config value (E1-5) so E11 can raise it (§12's ≤150 s option)  
- Heartbeat note: the 3 s grace sleeps on the poller thread — the background heartbeat thread keeps the session alive; callback time stays far under `max.poll.interval.ms`

**Out of scope:** dependency-pause interplay during shutdown (E8), drainer cancellation (zombies run on by design)

**Invariants that apply:** I7 — revocation protocol exactly: close → grace → commitSync → unregister → return · I1 — `wakeup()` only; the listener runs on the owner thread

**Acceptance criteria:**

- [ ] Revocation sequence ordering exactly markClosed → grace → commitSync(parts) → unregister, verified; assigned sequence register → reopen  
- [ ] After revocation, later async commit passes never include revoked partitions even as their local watermarks advance; zombie completions still release permits (E4-0's no-op path)  
- [ ] Shutdown from mid-load: intake stops, in-flight completes within grace, final sync commit lands, consumer closes cleanly  
- [ ] **Tests:** unit with mocked consumer/tracker/dispatcher — callback sequence (in-order verification incl. unregister); commit args \= committable(parts) at wm+1; commit-pass filtering with a revoked-but-advancing partition; shutdown happy path \+ grace-timeout path; wakeup interrupts a parked poll. \~100% branch  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.2 at the revocation commitSync too. The subtle bug class is committing zombie progress — "recorded locally, never committed" must survive refactors.

---

### **\[ \] \[E4-5\] Engine integration test**

**Epic:** E4 · **Depends on:** E4-4 · **Size:** L

**Spec sections to read first:** §18 (integration bullets: rebalance, general), §8 (all), §8.4 (I3 paragraph)

**Context:** The engine proven against real Kafka before any pipeline exists: Testcontainers broker, instrumented stub executor (configurable latency/failure, execution log with timestamps \+ thread identity). This is the bed E8's outage test later extends. Test-only diff, but large — review as carefully as production code.

**Scope — build this (Testcontainers Kafka; 6-partition topic per §4.1):**

- Scenario 1 — correctness under load: N events across M PRs, random stub latencies ⇒ all complete; per-PR executions strictly serial and in offset order (overlap detector — I2); final committed offset per partition \== watermark+1 \== log end (I3); permits return to 200 (I4)  
- Scenario 2 — backpressure: slow stub saturates in-flight ⇒ consumer pauses (lag plateaus), resumes and drains; zero unexpected rebalances during the stall (§8.1's structural claim, asserted)  
- Scenario 3 — cooperative rebalance (§18): second engine instance mid-load ⇒ per-PR serialization holds *globally* (merged execution logs; at-least-once re-executions permitted, interleaved same-PR executions are not); every input offset completes somewhere; committed offsets sane on both  
- Scenario 4 — coalescing burst: K same-PR events in one poll window ⇒ 1 executed \+ K−1 superseded completions; watermark covers all K  
- Scenario 5 — abrupt instance loss: hard-stop instance 1 (no graceful commit) ⇒ instance 2 inherits, re-receives uncommitted range, everything completes; completion set ⊇ input set  
- Deterministic seeds logged for every randomized run

**Out of scope:** WireMock/pipeline behavior (E5-10), breaker/outage scenarios (E8-4), retry topics

**Invariants that apply:** I2, I3, I4, I7 — this ticket is their system-level proof; I5 at engine level

**Acceptance criteria:**

- [ ] All five scenarios green, non-flaky (3 consecutive CI runs)  
- [ ] Assertions are invariant-based (overlap detectors, offset-set equality, permit accounting) — never sleep-and-hope  
- [ ] **Tests:** this ticket *is* the tests; the five scenarios are the required set  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** at-least-once semantics in assertions — scenarios 3/5 must permit duplicate *executions* and forbid lost offsets or interleaved same-PR work; asserting exactly-once would assert what the design deliberately doesn't promise

---

# **E5 — Core pipeline**

*Module home (ruled): pipeline, clients, and adapter live in `shared` (§2/§7's first-listed option). Milestone: after E5-9b the system runs webhook → posted review, all outcomes handled; E5-10 proves it in CI.*

### **\[ \] \[E5-0\] Reviews persistence \+ claim state machine**

**Epic:** E5 · **Depends on:** E2-3, E1-2, E1-5 · **Size:** L

**Spec sections to read first:** §5.1 (DDL \+ status-semantics block), §7.1 (step 6, every branch; steps 1–2 queries), §7.2 (row-transition consequences)

**Context:** The DB half of the correctness story: the `reviews` repository, legal status transitions, and the class-aware claim upsert — the counter must count errors, not claims (P1-2). Explicit SQL (JdbcTemplate-style — the spec writes literal queries). Includes the small `installations` read repository E5-3/E5-4 need.

**Scope — build this:**

- Transition guard encoding §5.1's state machine; any illegal transition throws (a bug per spec, not an outcome)  
- Claim per step 6, all v1 branches: no row → INSERT `PROCESSING, error_attempts=1, first_received_at=hdr` · `AWAITING_RETRY` \+ ERROR-class parked hop → `PROCESSING, error_attempts+=1` · `AWAITING_RETRY` \+ DEFER/BREAKER-class → `PROCESSING` only (P1-2) · stale `PROCESSING`/`POSTING` → `PROCESSING, error_attempts+=1` (poison/hard-crash backstop) · `SUCCEEDED` same `(i,r,p,sha,round)` → SKIP-race signal (caller repopulates marker) · `FAILED` \+ `replay=true` → `PROCESSING`, reset counters `1/0`, restamp `first_received_at`, clear `first_deferred_at` · backstop `error_attempts > 5` → `EXHAUSTED_POISON` signal  
- Step-1/4 dedup fallback query (`SUCCEEDED` by `(i,r,p,sha)`); step-2 rate count (`status IN ('PROCESSING','POSTING')` on `idx_reviews_rate`)  
- Handler writes: → `AWAITING_RETRY` (+`defer_attempts` variant), FAILED upsert for unclaimed terminals (C-6), SKIPPED upserts with reason, finalize update (`SUCCEEDED, github_review_id, completed_at`), `post_attempted_at=now()`, `first_deferred_at` set-if-null  
- `installations` reads: `api_key_encrypted` presence \+ value, `default_model`

**Out of scope:** `/rereview` force branch (v1.5 — claim rejects `force=true` loudly), fence usage (v2), Redis, pipeline flow

**Invariants that apply:** I5 — claim outcomes are what let every record terminate exactly once

**Acceptance criteria:**

- [ ] Every step-6 branch exact, incl. both P1-2 no-increment classes and full replay reset  
- [ ] Illegal transitions throw; legal matrix passes  
- [ ] **Tests:** Testcontainers Postgres — one test per claim branch (row-state × parked-class matrix); §5.1 transition-legality matrix; rate count includes `PROCESSING`\+`POSTING`, excludes `AWAITING_RETRY`/terminals; dedup query matches `SUCCEEDED` only; concurrent same-key claims (2 threads) → one winner \+ one race-signal via the unique constraint  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.14 surfaces here as: no repository method may write a dedup-relevant state for skips — SKIPPED rows carry reasons but never feed the dedup query

---

### **\[ \] \[E5-1\] GitHub client foundation**

**Epic:** E5 · **Depends on:** E1-2, E1-5 · **Size:** M

**Spec sections to read first:** §6.2 (O6 — App auth), §7.1 (steps 2.5, 3, 12c), §12 (GitHub HTTP row), §10.1 (ambiguous-403), §13.1 (listing requirement)

**Context:** One typed GitHub client in `shared` for every pipeline step. Authenticates as a GitHub App: JWT (RS256, app private key) exchanged for cached installation tokens (O6). All errors map through E1-2's `ProviderError`; breaker decoration is a seam E8 wraps.

**Scope — build this:**

- Installation auth (O6): app-id \+ PEM from secret config, **PEM under BYOK-grade secrecy** (never logged); JWT → `POST /app/installations/{id}/access_tokens`; per-installation cache, refresh \~5 min before expiry (\~1 h tokens, ⚠ VERIFY); 401 on exchange after one retry ⇒ credential/config alert metric (our side); 401/403 using a token that a forced refresh doesn't fix ⇒ `INSTALLATION_REVOKED`  
- Operations: `getPullRequest` (state, head sha, draft, updated\_at) · `getPullRequestFiles` paginated to exhaustion (the "full PR diff" as per-file patches — matches E1-4 `Diff`) · `createReview` → review id · `listReviews` **paginated to exhaustion** (P1-5) · `getCollaboratorPermission`  
- §12 timeouts (2 s/10 s); every non-2xx → `ProviderError` (status, headers, capped snippet) → classifier; call-decoration seam (functional wrapper) for E8  
- Rate-limit-remaining gauge per installation (§15)

**Out of scope:** contents/compare APIs (E9), breaker instances (E8), retry logic (outcomes drive retries)

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] All five operations green vs WireMock, auth transparent  
- [ ] **Tests:** WireMock — JWT→token exchange \+ cache \+ early-refresh \+ 401-refresh-retry \+ exchange-401-alert path; pagination walks 3 pages and stops; per-operation error-mapping matrix (404, 401/403 no-rate-limit-headers, 403+rate-limit-headers, 429+Retry-After, 500, timeout) asserting exact `ProviderError` fields; log-capture: PEM never in logs  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the ambiguous-403 rule (§10.1) is honored by *passing headers through* — the client never collapses status to an exception type itself

---

### **\[ \] \[E5-2\] Redis pipeline services: dedup \+ lock**

**Epic:** E5 · **Depends on:** E1-1, E1-5, E5-0 · **Size:** M

**Spec sections to read first:** §7.1 (steps 1, 5), §5.2 (dedup \+ lock rows, never-source-of-truth paragraph), §12 (lock rows), §19 (item 3\)

**Context:** The fast path: semantic dedup (Redis first, DB fallback, best-effort repopulate) and the cross-instance per-PR lock (Redisson watchdog — lock lifetime tracks holder lifetime, killing the fixed-TTL zombie source). Redis is never truth.

**Scope — build this:**

- `DedupService.check(i,r,p,sha,round)`: Redis `EXISTS` → hit=done · miss/down → E5-0 DB query → hit ⇒ best-effort repopulate \+ done · miss ⇒ proceed; `markReviewed(...)`: `SET EX 7d`, best-effort  
- `ReviewLockService.tryAcquire(i,r,p)`: Redisson `tryLock(waitTime=2 s)` **without `leaseTime`** (watchdog: 30 s TTL, \~10 s renewal); returns acquired | held (→ `DEFER(LOCK_HELD)`) | redis-down (→ `BREAKER_PARKED(redis)` signal); `release()` token-safe, built for `finally`  
- Redisson client wiring for the worker (2 s op timeout)

**Out of scope:** flow placement (E5-5/E5-9a), unified Redis-down pause (E8 — only the signal here), delivery dedup (E3-2)

**Invariants that apply:** I2 — this lock is the cross-instance half of per-PR serialization

**Acceptance criteria:**

- [ ] Dedup fallback \+ repopulate exact; `round` is part of the key (C-4)  
- [ ] Lock held across a simulated 40 s park stays held (watchdog renews past 30 s TTL), not stealable meanwhile; second contender gets `held` within \~2 s  
- [ ] **Tests:** Testcontainers Redis \+ real Redisson — the 40 s-park renewal test (asserts no `leaseTime` regression); contention; release-in-finally under exception; Redis stopped → dedup DB-fallback both ways, `markReviewed` swallows, `tryAcquire` reports redis-down within budget; dedup marker TTL ≈ 7 d  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.3 — any `leaseTime` silently disables the watchdog and reintroduces the zombie; the 40 s-park test makes that regression impossible. Release is Redisson's token-safe Lua — never hand-roll `DEL`.

---

### **\[ \] \[E5-3\] BYOK key crypto \+ Anthropic adapter**

**Epic:** E5 · **Depends on:** E1-4, E1-2, E1-5, E5-0 · **Size:** L — *pre-authorized in-flight split at the crypto | adapter seam (crypto lands first) if the session runs long*

**Spec sections to read first:** §14.1 (encryption-assembly, S-5), §7.1 (step 10), §14.2, §19 (item 12), §10.2 (S-4)

**Context:** Per ruling, the full key-crypto service (encrypt **and** decrypt) ships here so e2e fixtures can seed keys before E9's settings page. Plus the one v1 `ReviewGenerator` adapter: S-3-fenced prompt, structured findings, per-installation model resolution, §10.1-exact error mapping.

**Scope — build this:**

- `KeyCryptoService`: envelope encryption storing `{encrypted_DEK, nonce, ciphertext, key_version}` as distinct fields (S-5); AES-256-GCM, **fresh random 96-bit nonce per encryption**; DEK via a `KmsKeyProvider` seam (AWS KMS impl \+ deterministic test impl — prod wiring E11); decrypt failures ⇒ `MISSING_OR_INVALID_API_KEY`  
- `AnthropicReviewGenerator` (E1-4 port): 120 s total timeout; prompt requests `{file, summary, severity, detail}`; **S-3 fencing** — diff wrapped in explicit delimiters \+ instruction that fenced content may contain adversarial instructions and must never be obeyed; 2xx-unparseable ⇒ `TERMINAL(LLM_REQUEST)`  
- Error mapping per step 10: 400 → `TERMINAL(LLM_REQUEST)` · 429 → `DEFER(PROVIDER_THROTTLE)` per headers · 5xx/529/timeout → `TRANSIENT`; breaker seam as E5-1  
- Per-installation factory: key decrypted **in the adapter call path only**, held only for the call; model \= repo override else `installations.default_model`; key never logged, never in exceptions  
- Log hygiene (S-4): sizes and hashes, never diff/prompt content

**Out of scope:** encrypt-side UI/validation call (E9-1), chunking (v2), second provider

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Crypto round-trips; two encryptions of one plaintext differ in nonce and ciphertext; tamper fails authentication; `key_version` persisted  
- [ ] Golden prompt proves fencing: delimiters \+ adversarial warning present, diff strictly inside the fence  
- [ ] **Tests:** unit — crypto (round-trip, fresh-nonce, tamper, version); WireMock Anthropic — structured parse; 400/429+Retry-After/500/529/timeout matrix; 2xx-unparseable → `LLM_REQUEST`; 120 s budget honored; log-capture: no key material, no diff content; factory override-else-default  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.12 — GCM nonce reuse under one key forfeits confidentiality *and* authenticity; the fresh-nonce test is mandatory. Decrypt-in-adapter-only (§14.1): step 2.6 checks presence, never decrypts.

---

### **\[ \] \[E5-4\] Pipeline steps 1–4: gates \+ live fetch**

**Epic:** E5 · **Depends on:** E5-0, E5-1, E5-2 · **Size:** M

**Spec sections to read first:** §7 (intro), §7.1 (steps 1, 2, 2.5, 2.6, 3, 4), §10.1

**Context:** The front half of `processReview` in `shared`: plain sequential blocking code (runs on a drainer's virtual thread; I2 holds by construction). Cheapest checks first — an already-done, throttled, unauthorized, or keyless event must cost no LLM call and minimal GitHub. Every branch resolves to exactly one E1-2 outcome.

**Scope — build this:**

- Skeleton \+ steps in order: **1** dedup event-SHA → SKIP · **2** rate count ≥ cap 5 → `DEFER(RATE_LIMIT)` · **2.5** author authorization (S-1): permission via E5-1, policy from ctx repo-config (`write` default / `contributors` \= write OR prior merged contribution / `all`); ineligible → `SKIPPED(AUTHOR_NOT_PERMITTED)` upsert \+ SKIP; **in-memory per-worker cache keyed `(inst,repo,user)`, 5 min TTL, bounded (O7)** · **2.6** key presence (V-1): `api_key_encrypted` null → `TERMINAL(MISSING_OR_INVALID_API_KEY)`; presence only — decryptability proves itself in-adapter · **3** live fetch: closed/merged → `SKIPPED(PR_CLOSED)` upsert, **no dedup marker** · 404 → `TERMINAL(PR_NOT_FOUND)` · permission-class 401/403 → `TERMINAL(INSTALLATION_REVOKED)` · 429 → `DEFER` w/ `not_before` · 5xx/timeout → `TRANSIENT` · else `current = pr.head.sha`; `current ≠ event.head_sha` ⇒ proceed with `sha := current`, **no marker for the superseded SHA** (P1-1) · **4** dedup current-SHA → SKIP  
- Postgres/Redis op failure anywhere → `BREAKER_PARKED(dep)` (P1-4) — the catch-mapping covers the whole function

**Out of scope:** lock/claim (E5-5), everything after step 4, suspended check (v2, deferred appendix)

**Invariants that apply:** I2 — single-threaded per PR by construction; stays plain-sequential · I5

**Acceptance criteria:**

- [ ] Step order exactly 1→2→2.5→2.6→3→4; unauthorized author triggers no diff fetch; keyless install no GitHub fetch beyond the permission call  
- [ ] **Tests:** unit with mocked services — dedup hit (redis / db-fallback+repopulate); rate at/over/under cap; author matrix (3 policies × permission levels incl. `contributors` prior-merge) \+ cache hit within TTL; key present/absent; every step-3 branch incl. supersede-no-marker (asserts dedup service *not* written); PG/Redis exception anywhere → `BREAKER_PARKED(dep)` with no counter side effects  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.14 twice — closed-PR skip and superseded-SHA both must never write `dedup:` (P1-1; failure-matrix row 26\)

---

### **\[ \] \[E5-5\] Pipeline steps 5–6: lock \+ claim**

**Epic:** E5 · **Depends on:** E5-4 · **Size:** M

**Spec sections to read first:** §7.1 (steps 5, 6), §12 (lock rows), §13.1 (rows 2–4)

**Context:** The concurrency-critical pair: cross-instance lock (from here, **every** exit releases in `finally`), then the class-aware claim establishing ownership and detecting stale prior owners (lock free \+ row `PROCESSING` ⇒ prior owner dead). Claim branch logic is E5-0's; this ticket wires it with correct outcomes.

**Scope — build this:**

- Step 5: `tryAcquire` → held ⇒ `DEFER(LOCK_HELD)` · redis-down ⇒ `BREAKER_PARKED(redis)` · acquired ⇒ continue; establish the `try/finally` release discipline every later step lives inside — success-path unlock timing belongs to finalize (I8), so the `finally` is the safety net  
- Step 6: claim via E5-0 with `replay` flag \+ parked-hop class from headers (E1-0 codecs); SUCCEEDED-race ⇒ repopulate dedup marker (best-effort) → SKIP; `EXHAUSTED_POISON` backstop ⇒ TERMINAL; `force=true` ⇒ loud rejection (v1.5)  
- Attempt-context plumbing: `ctx` carries header-seeded counters (DB wins on conflict — seed only picks the increment branch)

**Out of scope:** steps 7+ (E5-6), unlock-after-marker ordering (E5-9a owns I8), fence (v2)

**Invariants that apply:** I2 — the lock is its cross-instance half · I5

**Acceptance criteria:**

- [ ] Lock-held defers without touching the claim; redis-down parks; acquired claims and proceeds  
- [ ] Any exception after acquisition releases the lock, verified for a mid-claim DB failure (→ `BREAKER_PARKED(dep)` **and** lock released)  
- [ ] **Tests:** unit — all three acquire results; claim-outcome wiring per branch (race→marker-repopulate→SKIP, backstop→TERMINAL, replay pass-through, force rejection); finally-release under injected exceptions at each point after acquisition; header-seeded counters passed through  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.3 transitively — no leaseTime, no unlock-on-success here (that's E5-9a's I8 ordering)

---

### **\[ \] \[E5-6\] Pipeline steps 7–11: config, diff, size guard, generate, filter**

**Epic:** E5 · **Depends on:** E5-5, E5-3, E5-1 · **Size:** M

**Spec sections to read first:** §7.1 (steps 7, 8 degenerate path, 9, 10, 11), §14.3, §12 (O8 rows)

**Context:** The middle: resolve config, fetch the diff, guard size, generate, filter. Per the approved E9 move, step 7 uses defaults via a seam (E9-2 swaps the loader in) and step 8 is the degenerate branch: base always absent ⇒ full PR diff. Both are spec-correct, permanently valid as fallbacks.

**Scope — build this:**

- Step 7: effective config \= E1-5 `RepoConfig` defaults \+ `installations.default_model`; `RepoConfigSource` seam returning defaults (E9-2 swaps the real loader — pipeline unchanged)  
- Step 8 (degenerate): full PR diff via `getPullRequestFiles` → E1-4 `Diff`; structured so E9-3 adds base-lookup \+ compare *before* it without touching this path (this path *is* the no-base/diverged fallback)  
- Step 9 (O8): token estimate \= chars/4 over all prompt-bound content; per-model budget map (default 150 000); trips at estimate \> 0.9 × budget ⇒ `TERMINAL(DIFF_TOO_LARGE)`; ⚠ VERIFY real context windows  
- Step 10: factory-resolved generate (E5-3); outcome mapping already in the adapter — this step routes  
- Step 11: drop findings `< min_severity` (E1-4 `atOrAbove`); zero remaining ⇒ flow still proceeds to post with the "reviewed {short-sha}: no significant issues" signal (rendering in E5-7)

**Out of scope:** `.reviewbot.yml` fetch/cache (E9-2), compare/incremental (E9-3), chunking (v2), body rendering (E5-7)

**Invariants that apply:** I5

**Acceptance criteria:**

- [ ] Oversized diff terminates before any LLM call; empty-after-filter still reaches post with the no-issues signal  
- [ ] **Tests:** unit — config resolution (default vs installation override); diff from paginated files; size-guard at/over/under the 0.9 boundary; generate outcome pass-through; filter matrix incl. zero-remaining; step order asserted (no generate when guard trips)  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** scope creep toward E9 — the seams exist so E9's diffs don't touch this code

---

### **\[ \] \[E5-7\] Step 12: body render, HMAC marker, blind post**

**Epic:** E5 · **Depends on:** E5-6 · **Size:** M

**Spec sections to read first:** §7.1 (steps 12a, 12c — *not* 12b), §13.1 (marker row), decision log \#11, ruling 6 (S-3 defang)

**Context:** The load-bearing side effect. v1 posts blind on the happy path — dedup and the lock established sole ownership; zero happy-path GitHub reads (log \#12). The marker makes the post recognizable and unforgeable: invisible HTML comment with an HMAC over `(inst,repo,pr,sha,round)` under the app secret (S-2).

**Scope — build this:**

- `MarkerCodec` in `shared`: render `<!-- reviewbot:v1 inst={i} repo={r} pr={p} sha={sha} round={n} sig={HMAC_appSecret(...)} -->`; parse \+ **constant-time HMAC verify**; canonical signed-tuple byte layout pinned by golden test  
- Body render: findings → markdown grouped per file (severity-labeled), zero-findings one-liner, marker appended; **S-3 defang** (ruling 6\) — `@mentions` and bare URLs in LLM-derived text backtick-wrapped so they neither ping nor link  
- Step 12 flow: (a) `post_attempted_at=now()` **before** the HTTP call → (c) `createReview` `{event:"COMMENT"}` → capture `github_review_id`; failures: refused/clean-5xx → `TRANSIENT` · timeout-after-send (ambiguous) → `TRANSIENT` (next attempt hits E5-8's guard) · 429 → `DEFER` · breaker-open → `BREAKER_PARKED`

**Out of scope:** the guard read (E5-8), finalize (E5-9a), find-or-create (v2)

**Invariants that apply:** I5; (a)-before-(c) is this ticket's load-bearing sequence — it makes the guard sound

**Acceptance criteria:**

- [ ] Marker round-trips; any altered tuple field fails verification; marker invisible in rendered markdown  
- [ ] `post_attempted_at` committed before the POST leaves, incl. on the timeout path  
- [ ] **Tests:** unit — marker golden render/parse/verify \+ per-field tamper matrix \+ constant-time compare; body goldens (findings, zero-findings, marker placement); defang cases (`@user`, bare URL, mixed) and non-cases (fenced code intact); WireMock — happy post captures id; refused/5xx/timeout/429 mapping; timeout path still has `post_attempted_at` set  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.6 constant-time compare applies to marker verification too. No pre-post existence read here — blind post is a decision (log \#12); the guard is deliberately retry-path-only.

---

### **\[ \] \[E5-8\] Post-attempt guard (§7.12b) \+ marker search**

**Epic:** E5 · **Depends on:** E5-7 · **Size:** M

**Spec sections to read first:** §7.1 (step 12b), §13.2, §13.1 (marker-matching paragraph \+ O9 note), §19 (item 11\)

**Context:** Closes the ambiguous-timeout double-post in v1 (rows 7–8): if `post_attempted_at` is non-null at claim on **any** branch, a prior attempt may have landed — search for the marker before posting; found ⇒ adopt its id, jump to finalize. One GitHub read, retries-after-post only, never on the happy path.

**Scope — build this:**

- `MarkerSearch` in `shared`: `listReviews` **filtered to the app's own bot user**, paginated **to exhaustion** (P1-5), parse candidates, **HMAC-verify**, match exact `(inst,repo,pr,sha,round)`; bot-user identity \= **configured bot login verified at startup against the App identity endpoint, fail-fast on mismatch, cached for process lifetime (O9, ⚠ VERIFY endpoint)**  
- Guard wiring in step 12: claim-context carries `post_attempted_at`; non-null ⇒ search **before** (a)/(c) — found ⇒ adopt `github_review_id`, signal jump-to-finalize (E5-9a consumes) · absent ⇒ post normally  
- Search-failure routing: 5xx/timeout → `TRANSIENT` (never "assume absent and post"), 429 → `DEFER`, breaker → `BREAKER_PARKED`

**Out of scope:** reconciliation-before-LLM, find-or-create, sweep (v2 — §13.3's *read* is v1-scoped per step 12b, the rest is not)

**Invariants that apply:** I5

**Acceptance criteria:**

- [ ] Retry-after-ambiguous-timeout adopts the existing post — WireMock verifies exactly one create total  
- [ ] A forged marker (non-bot author, or bot-authored bad HMAC) never adopted; search paginates past page 1  
- [ ] **Tests:** §18 verbatim — multi-page WireMock listing, marker on page 3, no duplicate post (P1-5); forged-marker by non-bot author ignored (S-2); bot-authored invalid sig ignored; wrong round/sha unmatched; guard skipped when `post_attempted_at` null (zero list calls on happy path); search-failure routing matrix; startup bot-login mismatch fails boot  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.11 verbatim — never match without bot-user filter AND HMAC verify AND exhaustive pagination; a first-page-only scan reopens the duplicate-post hole on long PRs

---

### **\[ \] \[E5-9a\] Finalize \+ SUCCESS/SKIP \+ worker wiring \+ producer**

**Epic:** E5 · **Depends on:** E5-8, E4-2 · **Size:** M

**Spec sections to read first:** §7.1 (step 13 \+ rationale paragraph), §7.2 (SUCCESS/SKIP rows \+ `markComplete` contract), §4.2 (worker producer), §19 (items 4, 14\)

**Context:** First half of the outcome layer, split at the I8/I9 seam: finalize in the I8 order (DB → Redis marker → unlock → SUCCESS), the SUCCESS/SKIP handler rows, and the real `JobExecutor`/`CompletionHandler` replacing E4-3's stubs. After this ticket the *happy path* runs webhook → posted review; failure outcomes fail loudly until E5-9b.

**Scope — build this:**

- Finalize (step 13), order load-bearing: `SUCCEEDED + github_review_id + completed_at` → dedup marker `SET EX 7d` best-effort → unlock → `SUCCESS`; the guard's jump-to-finalize lands here (adopted id, same order)  
- Worker producer for retry/DLQ per §4.2 (used by E5-9b; config \+ wiring land here); headers via E1-0 codecs only  
- `CompletionHandler` skeleton: `SUCCESS`/`SKIP` rows complete (nothing further → `markComplete`); outcomes counter metric `{outcome × class}` (§15) emitted for **all** outcomes at the skeleton level; **unimplemented rows (DEFER/TRANSIENT/TERMINAL/BREAKER\_PARKED) \= temporary loud path: error log \+ `handler_unimplemented` metric \+ `markComplete`** — pinned by test, replaced by E5-9b (I5 holds mechanically; semantics complete at 9b)  
- Real `JobExecutor` \= `processReview`; review-worker wiring complete (E4-3 stub beans replaced)

**Out of scope:** the four failure-handler rows \+ V-2 (E5-9b), retry-worker (E6), DLQ indexer (E7)

**Invariants that apply:** I8 — DB→Redis→unlock, exactly · I5 — skeleton guarantees exactly-one `markComplete` per outcome · I4 — completion is the permit's one door (via tracker)

**Acceptance criteria:**

- [ ] Finalize order verified by in-order mock verification; unlock strictly after marker write; a crash injected between DB and Redis leaves a DB-recoverable state (dedup fallback works)  
- [ ] Happy path runs end-to-end against compose (manual smoke noted in PROGRESS.md): webhook → posted review with valid marker  
- [ ] **Tests:** unit — finalize ordering; SUCCESS/SKIP rows; jump-to-finalize with adopted id; temporary-loud-path pinning test (unimplemented outcome ⇒ log \+ metric \+ markComplete, never silent); producer config golden per §4.2; integration-lite (Testcontainers) — one SUCCESS flows claim→post→finalize→marker present  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.4 — I8 is a load-bearing ordering, not style. §19.14 — the dedup marker is written *here and only here* (success), nowhere else in the system.

---

### **\[ \] \[E5-9b\] Failure outcome handlers \+ V-2 — milestone ticket**

**Epic:** E5 · **Depends on:** E5-9a, E1-3 · **Size:** M

**Spec sections to read first:** §7.2 (entire table \+ I9 line), §17 (row 37, V-2), §12 (backoff \+ SLA rows), §19 (items 4, 13\)

**Context:** Second half at the I9 seam: the four failure rows, each ending in produce-then-`markComplete`. Exhaustion is decided **here** (C-2 — the single source of truth the retry worker reuses via E6-1); the defer SLA measures deferral time, never receipt time (C-3). After this ticket the system runs end-to-end with every outcome handled — the backlog milestone.

**Scope — build this (replacing E5-9a's temporary loud path row by row):**

- `DEFER` — `first_deferred_at` set-if-null; `now − first_deferred_at > 2 h` ⇒ escalate `TERMINAL(COULD_NOT_SCHEDULE)` (C-3); else `AWAITING_RETRY`, `defer_attempts+=1`, produce retry (counters, class, `not_before` per defer schedule), unlock-if-held  
- `TRANSIENT` — **exhaustion decided here** (C-2): `error_attempts >= 5` ⇒ `TERMINAL(EXHAUSTED_{class})` with the E1-3 envelope from the exception in hand; else `AWAITING_RETRY`, produce retry (`not_before = errorSchedule[error_attempts]`), unlock  
- `TERMINAL` — E1-3 envelope → produce DLQ; row FAILED (UPDATE if claimed, else UPSERT — C-6); unlock  
- `BREAKER_PARKED` — **no counter changes**, `AWAITING_RETRY` if claimed, produce retry `now + 60 s`, unlock  
- **I9 everywhere:** produce to destination *before* `markComplete`, every hop  
- V-2 (row 37): handler's own produce failure ⇒ bounded-backoff retry of the produce **while holding the permit** (intake stalls — desired) \+ `handler_produce_failure` alert metric; handler DB writes best-effort (claim reconciles)

**Out of scope:** retry-worker consumption (E6), DLQ indexing (E7), breaker instances (E8)

**Invariants that apply:** I9 — produce-then-complete on every hop · I5 — every failure row ends in exactly one `markComplete` · I8 — untouched (E5-9a's)

**Acceptance criteria:**

- [ ] Every §7.2 row exact, incl. both no-counter classes and the 2 h boundary (at / just-under / just-over)  
- [ ] Produce-then-complete asserted per hop (ordering mocks): retry before markComplete, DLQ before markComplete  
- [ ] **Tests:** unit — full handler matrix (rows × claimed/unclaimed); C-2 exhaustion at exactly 5 with envelope-from-exception; C-3 clock semantics (old `first_received_at` \+ fresh first defer does NOT escalate); V-2 produce-failure loop (bounded, permit held, metric fires); integration (Testcontainers Kafka+PG+Redis, WireMock) — one DEFER round-trips to the retry topic with codec-correct headers; one TERMINAL lands on DLQ with a complete E1-3 envelope  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.4 — I9 is load-bearing; any "cleanup" that reorders reopens designed-out failures. §19.13 — headers only via shared codecs.

---

### **\[ \] \[E5-10\] End-to-end happy path \+ idempotency integration test**

**Epic:** E5 · **Depends on:** E5-9b, E3-3 · **Size:** L

**Spec sections to read first:** §18 (happy path, duplicate delivery, crash-sim bullets), §17 (rows 3, 5, 7, 26), §13.2

**Context:** The milestone proof: webhook in, review comment out, through real gateway, real Kafka, real engine, real pipeline, WireMock providers. Plus the idempotency claims that are the design's point, as failure-matrix rows. Crash-sim runs as in-JVM fault injection at the specified seam; container-kill chaos stays out of a solo-dev v1.

**Scope — build this (Testcontainers Kafka/PG/Redis, WireMock GitHub+Anthropic, real gateway HTTP \+ worker):**

- Happy path: signed webhook → exactly one `createReview` (WireMock verify), body has findings \+ valid marker (parse+verify with app secret), row `SUCCEEDED` with id, dedup marker present, offsets committed  
- Duplicate delivery (rows 1/3): same delivery id twice \+ same-SHA semantic duplicate ⇒ still one create  
- Crash-after-post-before-finalize (row 7): fault-hook throws post-POST; redelivery ⇒ guard finds marker, adopts, repairs row to `SUCCEEDED`; total creates \== 1  
- Supersede-then-revert (row 26): event SHA A superseded by B mid-flight (WireMock PR returns B) ⇒ B reviewed, no marker for A; revert to A ⇒ A reviewed normally (2 creates, one per SHA)  
- Fixture: installation row seeded with an E5-3-encrypted key (test `KmsKeyProvider`) — this harness is reused by E7-2, E8-4, E9-1, E9-3

**Out of scope:** retry-topic flows (E6-2 extends this bed), breaker storms (E8-4), rebalance (E4-5)

**Invariants that apply:** system-level proof of I5, I8, I9 on the happy path; rows 7/26 demonstrate §13.2's v1 posture

**Acceptance criteria:**

- [ ] All four scenarios green, non-flaky (3 consecutive CI runs); assertions WireMock-verify \+ DB-state based, never sleep-and-hope  
- [ ] **Tests:** this ticket is the tests; the four scenarios are the required set  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the row-26 assertion must check the *absence* of a dedup marker for SHA A after supersession (§19.14) — asserting only "A eventually reviewed" would pass with the P1-1 bug present

---

# **E6 — Retry worker**

### **\[ \] \[E6-0\] Pause-until-due retry consumer loop**

**Epic:** E6 · **Depends on:** E2-0, E1-0, E1-5 · **Size:** M

**Spec sections to read first:** §9 (items 1–2 \+ role-split paragraph), §4.3, §12 (poll timeout; `max.poll.interval` rows)

**Context:** The retry-worker's scheduling half: a sequential per-partition consumer that waits for `not_before` by pausing partitions and continuing to poll — never by sleeping. Heartbeats flow throughout, so a 15-minute backoff at a partition head costs nothing in group liveness. Processing is a stub seam; E6-1 plugs in the core.

**Scope — build this:**

- `KafkaConsumer` per §4.3 (group `retry-worker`, manual commits, `CooperativeStickyAssignor`, `earliest`), retry topic; single-threaded loop, `poll(200 ms)`  
- Due-check per record in partition order: `not_before` via E1-0 codecs; not due ⇒ `pause(tp)` \+ `seek(tp, record.offset)` so the not-due record (and everything fetched behind it) re-delivers on resume; record `tp → dueAt`  
- Each cycle: any paused `tp` with `now >= dueAt` **and** no dependency pause ⇒ `resume(tp)`; `volatile dependencyPause` flag (E8 wires it, §9 item 4\) composes into the resume predicate like E4-3's  
- Due records to an injected processor seam (stub: log \+ commit); missing `not_before` ⇒ due now \+ warn metric (defensive)  
- Per-partition independence: A paused-not-due must not delay due records on B/C

**Out of scope:** `processReview`/handler wiring, counter seeding, produce-on-outcome (E6-1); `retry_delay_excess` (E6-2); breaker instances (E8)

**Invariants that apply:** I1 — one thread owns this consumer too · I6 — pause/resume predicate composes due-time with dependency health, no inversion

**Acceptance criteria:**

- [ ] Far-future `not_before` at a head keeps the loop polling (poll count grows, thread never blocks beyond poll timeout) and the consumer in-group; the record processes only once due  
- [ ] Due record on B processes while A sits paused  
- [ ] **Tests:** unit (`MockConsumer`) — pause+seek on not-due (seek offset asserted), resume at due, dependencyPause blocks resume of a due partition, missing-header defensive path, per-partition independence, no-`Thread.sleep` structure (poll invoked every cycle during a long wait); integration (Testcontainers Kafka) — `not_before = now+5 s` processed no earlier than due (±1 s), no rebalance during the wait  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §9's own warning — `Thread.sleep` through the wait violates `max.poll.interval.ms` at exactly the backoff lengths this topic exists for; pause/poll is the only correct wait. §19.13 — `not_before` via shared codecs only.

---

### **\[ \] \[E6-1\] Retry processing: one core, both workers**

**Epic:** E6 · **Depends on:** E6-0, E5-9b · **Size:** M

**Spec sections to read first:** §9 (item 3, verbatim), §7.2 (the shared handler — C-2 line), §3.2, §19 (items 4, 13\)

**Context:** The C-2 seam made real: the retry worker calls the *same* `processReview` and the *same* §7.2 outcome-action logic — one implementation, both paths. Only the completion strategy differs: no engine here, so completion \= produce-then-`commitSync` per record. Exhaustion is decided in the shared handler, never re-implemented.

**Scope — build this:**

- Replace E6-0's stub: envelope deserialization (E1-0; validation failure ⇒ `POISON_DESERIALIZATION` → E1-3 RAW envelope (with key/headers from the record) → produce → commit — same I9 shape); `ctx` seeded from headers via codecs (all five; DB wins on conflict — seed only selects the claim's increment branch)  
- Invoke `processReview(event, ctx)`; route through the shared §7.2 outcome-action component from E5-9b — if any action logic is engine-coupled, extracting it into the shared component is in-scope (behavior unchanged; E5-9a/b tests keep passing untouched)  
- Completion strategy: produce to destination (retry re-hop with next `not_before`, or DLQ) **before** `commitSync(offset+1)` (I9, §19.2's \+1); success/skip ⇒ commit directly; a record never lacks a durable home when its offset commits  
- Worker producer per §4.2 (reuse E5-9a's config); retry-worker Spring app wiring complete

**Out of scope:** breaker wiring (E8 — E6-0's flag is the seam), `retry_delay_excess` (E6-2), any divergence from §7.2 ("retry-specific handling" is by definition a bug)

**Invariants that apply:** I9 — produce-then-commit on every hop, `commitSync` as the complete · I5 · I2 — the Redisson lock still guards cross-instance overlap with the review-worker; do not weaken

**Acceptance criteria:**

- [ ] ERROR-class parked hop increments `error_attempts` on claim; DEFER/BREAKER-class does not (P1-2 through the retry path)  
- [ ] Exhaustion at attempt 5 dead-letters via the shared handler with a complete envelope; under-budget re-hops carry `errorSchedule[error_attempts]`  
- [ ] Produce lands before commit on every branch (in-order verification), incl. poison  
- [ ] **Tests:** unit — header→ctx seeding matrix (five headers, codec round-trip); outcome routing per §7.2 row with commit-strategy assertions; poison → RAW-with-key/headers → DLQ-then-commit; DB-wins-on-conflict (header 3, row 4 ⇒ row governs); integration (Testcontainers Kafka+PG+Redis, WireMock) — one transient-failed event re-hops with correct headers and succeeds through the retry worker on WireMock recovery  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.4 — I9 here is produce-then-**commitSync**; committing first loses the record on crash. §19.13 — a codec mismatch between workers is the named silent interop bug. C-2 — special-casing exhaustion in the retry worker reopens the dual-source-of-truth bug the spec closed.

---

### **\[ \] \[E6-2\] `retry_delay_excess` \+ retry-flow integration test**

**Epic:** E6 · **Depends on:** E6-1 · **Size:** M

**Spec sections to read first:** §9 (head-of-line caveat), §18 (retry bullet), §12 (error/defer backoff rows), §15 (`retry_delay_excess`)

**Context:** The honest-limitation instrumentation: within one partition, a long defer at the head blocks a short retry behind it — tolerated at deploy scale, instrumented from day one so the split-topics upgrade triggers on data. Plus the full-loop integration proof, using config-injected millisecond backoff schedules.

**Scope — build this:**

- `retry_delay_excess` timer/histogram: `actual_start − not_before`, clamped at 0, tagged by `failure_class`; emitted from the E6-0 loop at hand-off time (alert rule is E10-2)  
- Integration scenarios (bed from E6-1, ms backoff test profile):  
  - **Exhaustion loop:** persistent WireMock 500 ⇒ retry×4 with ascending `errorSchedule` header values, 5th attempt dead-letters — envelope asserts `error_attempts=5`, class `EXHAUSTED_*`, `original_event` intact  
  - **Defer SLA (C-3):** persistent 429 ⇒ defer hops, `defer_attempts` climbing, `first_deferred_at` stable from first defer; with a test-shrunk SLA window, escalation to `COULD_NOT_SCHEDULE` fires on elapsed-*deferral*\-time, not hop count  
  - **Head-of-line \+ metric:** long-`not_before` head with a short-retry record behind it on the *same* partition ⇒ short one starts late, `retry_delay_excess` ≈ the blockage; a due record on *another* partition unaffected (proven both ways)  
  - **In-order per PR:** two retry hops for one PR process in offset order  
- Deterministic seeds/clocks

**Out of scope:** split retry topics / tiered delays (documented scale step), alert rules (E10-2), breaker-pause interplay (E8-4)

**Invariants that apply:** I9 — the exhaustion scenario is its system-level proof on the retry path

**Acceptance criteria:**

- [ ] All four scenarios green, non-flaky (3 consecutive runs); backoffs asserted from headers via codecs, not sleeps  
- [ ] Metric visible with correct tags and plausible values in head-of-line; clamped-at-zero verified for on-time records  
- [ ] **Tests:** this ticket is the tests; the four scenarios are the required set  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the C-3 assertion must distinguish deferral-clock from receipt-clock — a scenario that only shrinks the SLA can pass with the wrong (receipt-based) implementation; age both and assert no escalation until *deferral* time exceeds the window

---

# **E7 — DLQ surface**

### **\[ \] \[E7-0\] DLQ indexer**

**Epic:** E7 · **Depends on:** E5-9b, E2-3 · **Size:** M

**Spec sections to read first:** §10.3 (first paragraph), §5.1 (`dlq_entries` DDL \+ D-5), §10.2 (both variants incl. O10 fields), §4.3

**Context:** A small dedicated consumer thread inside review-worker copying every DLQ message into `dlq_entries` — the DB is the browse/act surface. Crash-safety comes from `UNIQUE (dlq_partition, dlq_offset)` (D-5). The indexer must never die on bad input — a broken browse surface during a flood is the V-4 failure. (O10 fields are captured upstream at envelope build; the indexer persists.)

**Scope — build this:**

- Sequential consumer, own group id (`dlq-indexer`), §4.3-consistent config (manual commits, `earliest`, `CooperativeStickyAssignor`) pinned by golden test; subscribed to `pr.review.dlq`  
- Per message: standard envelope ⇒ identity columns from `metadata`, full envelope into JSONB · RAW variant ⇒ null identity, `failure_class` from the Kafka header (body has none; headers mirrored per §10.2), O10 fields persisted inside the envelope JSONB · unparseable DLQ message (defensive) ⇒ row with header-else-`POISON_DESERIALIZATION` class, envelope \= minimal b64 wrapper, warn metric — never a crash-loop  
- Ordering per record: `INSERT ... ON CONFLICT (dlq_partition, dlq_offset) DO NOTHING` → commit (durable home before commit — I9 discipline on a consume→DB hop); `status` defaults `NEW`  
- Metrics: `dlq_indexed_total{failure_class}`; `dlq_indexer_lag` gauge (V-4)  
- Lifecycle: started/stopped with the worker; DB down ⇒ §11 health-pause covers the worker — indexer stops committing and resumes (no counters, no skips)

**Out of scope:** admin endpoints (E7-1), alert rules (E10-2), bulk operations and purge (deferred)

**Invariants that apply:** I9 — adapted: the row insert is the "produce"; commit only after the durable home exists

**Acceptance criteria:**

- [ ] Every DLQ message becomes exactly one row; redeliveries insert nothing (conflict path verified)  
- [ ] RAW rows carry null identity \+ header-sourced class \+ O10 fields intact in JSONB; a corrupted DLQ message is indexed defensively and consumption continues  
- [ ] **Tests:** integration (Testcontainers Kafka+PG) — standard envelope indexed, all columns asserted; RAW variant (null identity, header class, O10 fields present); corrupt message → defensive row \+ continued consumption; duplicate delivery → one row; insert-before-commit (kill between insert and commit via hook → restart → no duplicate row, offset re-consumed); `dlq_indexer_lag` reflects an induced backlog; consumer-config golden  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** header codecs (§19.13) for header reads; the defensive path must not "fix" bad envelopes — store what arrived, annotate nothing

---

### **\[ \] \[E7-1\] Admin endpoints: list / replay / discard**

**Epic:** E7 · **Depends on:** E7-0, E3-3 · **Size:** M

**Spec sections to read first:** §10.3 (endpoints \+ playbook \+ replay semantics; v1.2 carries the O10 split), §2 (admin placement \+ protection), §3.1 (`replay` field)

**Context:** The human action surface, internal-only inside review-worker. Two replay species (O10): **enveloped** entries re-produce `original_event` with `replay=true` so the claim takes `FAILED→PROCESSING` and resets counters (P2-2); **RAW/poison** entries re-produce the verbatim payload bytes with the original key \+ headers plus a `dlq_replay=true` tracing header — no `replay` flag, because a poison record never deserialized and no FAILED row exists; a fixed reader handles it as a fresh event.

**Scope — build this:**

- `GET /admin/dlq?status=&class=&installation=` — filtered, `created_at DESC`, limit/offset paging, row projection incl. envelope  
- `POST /admin/dlq/{id}/replay`, enveloped: `original_event` **verbatim, flipping only `replay=true`** (`JsonNode`\-level — unknown fields preserved, never POJO round-trip) → produce to `pr.review.requested`, key `inst:repo:pr` → on ack, `REPLAYED` \+ `resolved_at` (produce-then-mark; a crash between leaves `NEW`; duplicate replay absorbed by claim/dedup)  
- Same endpoint, RAW (O10): re-produce `raw_payload_b64` bytes verbatim with `original_key_b64` (null ⇒ null key) and `original_headers`, **plus header `dlq_replay=true`**; no payload mutation → on ack, `REPLAYED` \+ `resolved_at`  
- `POST /admin/dlq/{id}/discard`: `DISCARDED` \+ `resolved_at`; already-resolved rows → `409` (both endpoints)  
- Protection per §2: HTTP basic auth on `/admin/**` (secret-config creds); no-public-ingress is E11-0's network policy

**Out of scope:** bulk replay/discard, auto-discard automation, purge job (deferred); any UI

**Invariants that apply:** I9 — adapted: produce the replay before marking the row resolved

**Acceptance criteria:**

- [ ] Enveloped replay re-produces with only `replay=true` changed (planted unknown field survives); row `NEW→REPLAYED` only after ack  
- [ ] RAW replay re-produces byte-identical payload with original key \+ headers \+ `dlq_replay=true`; verified against a captured poison entry  
- [ ] List filters compose (`idx_dlq_status`/`idx_dlq_tenant` exercised); unauthenticated → `401`  
- [ ] **Tests:** integration (Testcontainers Kafka+PG) — enveloped replay happy path (key, flag, verbatim body); RAW replay (bytes/key/headers/tracing-header asserted); produce-failure → row stays `NEW`, `5xx`; discard; double-resolve `409`; filter matrix \+ paging; auth 401/200  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.4 (I9-adapted) — mark-then-produce silently loses the replay on crash; the enveloped flag flip must not normalize the stored envelope (tolerant-reader loss — the trap E1-3 closed at build time)

---

### **\[ \] \[E7-2\] DLQ metrics \+ resolution integration test**

**Epic:** E7 · **Depends on:** E7-1, E6-1, E5-10 · **Size:** M

**Spec sections to read first:** §18 (DLQ bullet, verbatim), §10.3 (replay-through-full-pipeline, C-3 restamp; O10 playbook line), §15 (DLQ metric entries)

**Context:** Proves the DLQ story end to end at the §18 level: poison in, complete raw envelope indexed; an *exhausted* entry replayed to success with counters reset; and — per O10 — a poison entry replayed successfully after a "fix." Plus the P2-5 metric surface E10-2's alerts consume.

**Scope — build this:**

- `dlq_entries_new{failure_class}` gauge: scheduled query over `status='NEW'` by class (\~30 s interval, config)  
- Integration scenarios (E5-10 harness, ms backoffs):  
  - **Poison → raw variant (§18):** garbage bytes onto the main topic ⇒ DLQ message is the RAW variant, sentinel key, all fields incl. O10 key/headers ⇒ indexed with null identity  
  - **EXHAUSTED replay (P2-2, §18):** drive one event to exhaustion ⇒ `FAILED` row, DLQ'd, indexed ⇒ replay ⇒ claim `FAILED→PROCESSING`, `error_attempts` reset to 1, `defer_attempts=0`, `first_received_at` restamped, `first_deferred_at` cleared (C-3) ⇒ WireMock healthy ⇒ `SUCCEEDED`; dlq row `REPLAYED`  
  - **Poison replay after fix (O10):** the poison entry from scenario 1, replayed with the test consumer's deserializer now accepting it (fixture "fix") ⇒ record re-produced verbatim with original key \+ `dlq_replay=true` ⇒ processed as a fresh event through the normal pipeline ⇒ `SUCCEEDED`  
  - **Replay into clean SKIP (§10.3):** replay an entry whose PR is now closed ⇒ full pipeline ⇒ `SKIPPED(PR_CLOSED)`, **no dedup marker written**, no error  
  - **Metric truth:** during the exhaustion scenario, `dlq_entries_new` rises for the class, then falls to zero after resolution  
- Deterministic seeds/clocks; counter/timestamp assertions read the DB, not logs

**Out of scope:** alert rules (E10-2), bulk/purge (deferred), `retry_delay_excess` (E6-2)

**Invariants that apply:** I5 — replay records terminate exactly once; the scenarios prove the claim-path edges (P2-2, C-3, O10)

**Acceptance criteria:**

- [ ] All five scenarios green, non-flaky (3 consecutive runs)  
- [ ] Counter-reset assertion checks all four fields (both counters, both timestamps) — a partial reset is the P2-2 bug in disguise  
- [ ] **Tests:** this ticket is the tests; the five scenarios are the required set  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.14 — the clean-SKIP scenario must assert the dedup marker's *absence* (a closed-PR replay writing a marker suppresses a legitimate review after reopen — P1-1 through the replay door)

---

# **E8 — Degraded modes**

### **\[ \] \[E8-0\] Provider breakers \+ client decoration**

**Epic:** E8 · **Depends on:** E5-1, E5-3, E1-2 · **Size:** M

**Spec sections to read first:** §11.2 (config block \+ `ignoreExceptions` comment), §11.1 (GitHub/Anthropic rows), §19 (item 9), §10.1 (breaker-open row)

**Context:** One Resilience4j `CircuitBreaker` per provider — `github`, `anthropic` — around the call seams E5-1/E5-3 exposed. The critical subtlety is the exception lists: terminal classes say nothing about provider health, so a wave of 404s must never pause the system (§19.9); that's why `TerminalReviewException` exists.

**Scope — build this:**

- Both instances with §11.2 values verbatim (window 20, min calls 10, 50%, 60 s open, 2 half-open permits, `ignoreExceptions: [TerminalReviewException]`, `recordExceptions` mapped to the project's actual transient exception types)  
- Decoration of **every** GitHub and Anthropic call path through the seams — incl. the E5-8 guard read and the collaborator/permission calls  
- `CallNotPermittedException` (OPEN) → classified `BREAKER_PARKED` via E1-2 — short-circuit, no HTTP, no counter effects (the E5-9b handler does the rest)  
- Breaker state-transition events published internally (E8-1 subscribes); state metric per provider (§15)

**Out of scope:** pause wiring (E8-1), health-check breakers (E8-2), probes (E8-3), threshold tuning (deferred)

**Invariants that apply:** none directly (the pause predicate is I6, wired in E8-1)

**Acceptance criteria:**

- [ ] A 503 storm trips OPEN at the configured window/threshold; subsequent calls short-circuit to `BREAKER_PARKED` without reaching WireMock (zero requests while OPEN, verified)  
- [ ] A wave of 404s/permission-terminals leaves the breaker CLOSED (§19.9 proven)  
- [ ] **Tests:** unit — config golden vs §11.2; WireMock — trip-at-threshold; terminal-wave-stays-closed; OPEN short-circuit with correct outcome; transition events CLOSED→OPEN→HALF\_OPEN observed; decorated-coverage test: every client operation routes through its breaker (extended by E9-2/E9-3 when they add operations)  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.9 verbatim — the `ignoreExceptions` list is the whole point; mapping the project's terminal exception into it is load-bearing, not config trivia

---

### **\[ \] \[E8-1\] Dependency gate: breaker → pause wiring**

**Epic:** E8 · **Depends on:** E8-0, E4-3, E6-0 · **Size:** M

**Spec sections to read first:** §11.2 ("Wiring" paragraph — this ticket is those \~30 lines), §8.1 (composed predicate — P1-3), §11 (organizing rule), §15 (`pause_state`)

**Context:** Breaker state transitions drive a shared `DependencyGate` whose boolean is the `dependencyPause` flag both workers consume (E4-3, E6-0). Resume requires *all* required dependencies healthy — without the composed predicate, the poller resumes intake during an outage and churns the backlog onto the retry topic.

**Scope — build this:**

- `DependencyGate` in `shared`: set of unhealthy dependency names; `paused() = !set.isEmpty()`; per-reason exposure for the gauge  
- Subscriptions: `github`/`anthropic` OPEN **and HALF\_OPEN** ⇒ add (intake stays paused; probes, not records, are the half-open callers); CLOSED ⇒ remove; wired identically into both workers (§9 item 4\)  
- `pause_state` gauge per §15: `reason=backpressure|github|anthropic|redis|postgres` (backpressure from E4-3; redis/postgres populated by E8-2 — label set complete now)  
- Required-dependency set is total in v1 (all four gate both workers — §11.1 has no partial-processing mode)

**Out of scope:** health-check inputs (E8-2), probes (E8-3), per-dependency partial resumption (no v1 mechanism)

**Invariants that apply:** I6 — resume requires `!dependencyPause` AND permits; this ticket supplies the first conjunct's truth

**Acceptance criteria:**

- [ ] OPEN on either provider pauses both workers; CLOSED on one while the other is OPEN keeps them paused; all-CLOSED resumes  
- [ ] HALF\_OPEN keeps intake paused (records must not consume half-open permits meant for probes)  
- [ ] **Tests:** unit — gate set semantics (add/remove/multi-dep matrix, HALF\_OPEN); integration-lite (MockConsumer pollers \+ real breaker instances) — trip github ⇒ both flags true within one transition; heal ⇒ both false; trip both, heal one ⇒ still paused; gauge reflects the exact reason set through the sequence  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** P1-3 (§8.1/§11.2) — the flag must gate *resume*, not just trigger pause; wiring that only calls `pause()` on OPEN and lets the permit condition resume alone rebuilds the backlog-churn bug the composed predicate prevents

---

### **\[ \] \[E8-2\] Postgres/Redis health-check breakers**

**Epic:** E8 · **Depends on:** E8-1, E1-5 · **Size:** M

**Spec sections to read first:** §11.2 (P2-3 paragraph), §11.1 (Redis-lock \+ Postgres rows, one-switch footnote), §12 (health-check rows — v1.2 carries the O11 values)

**Context:** Health checks acting as breakers with anti-flap semantics: a single failed ping must not pause the fleet, a single success must not resume it. v1 Redis policy is deliberately one switch — the lock sits in every path, so partial fail-open just wastes work before stalling at the lock.

**Scope — build this:**

- Scheduled probes on the O11 cadence (**5 s**): Postgres `SELECT 1`, Redis `PING`, on §12 op-timeout budgets  
- Anti-flap state machine (O11, ruled): **3 consecutive failures ⇒ unhealthy** (gate add, reason `postgres`/`redis`); resume requires **3 consecutive successes AND a span ≥ 30 s, where any failure resets both the count and the span clock, and the span starts at the first success after the last failure** (≈ 7 consecutive passes at 5 s cadence); pause detected ≤ \~15 s  
- Both workers affected via the E8-1 gate; per §11.1: Postgres down pauses workers, gateway keeps ingesting (nothing to build — the gateway's event path never had DB access; O13.3: `/settings` 503s independently); Redis down \= unified pause of all processing  
- Transition metric \+ structured log per state change (§11.2)  
- Comment-only note where v2's fail-open flag would slot — no flag built

**Out of scope:** the v2 Redis fail-open option, gateway behavior, alert rules (E10-2)

**Invariants that apply:** I6 — same conjunct as E8-1, second source

**Acceptance criteria:**

- [ ] Two failed pings then success ⇒ no pause; three consecutive ⇒ pause; recovery requires count AND span (fast triple-success before 30 s does not resume; a failure mid-recovery resets both)  
- [ ] **Tests:** unit — anti-flap matrix with a fake clock (2-fail recovery, 3-fail trip, flapping sequences never resume early, span-starts-at-first-success-after-failure asserted, reset-on-failure asserted); integration (Testcontainers) — stop Postgres ⇒ workers pause with reason `postgres` after exactly 3 intervals; restart ⇒ resume after the anti-flap window; same for Redis; gauge labels correct throughout  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the one-switch footnote (§11.1) — do not "improve" Redis handling into per-role fail-open in v1; the dedup fail-opens (E3-2/E5-2) are orthogonal to this pause authority

---

### **\[ \] \[E8-3\] Recovery probes (P2-1)**

**Epic:** E8 · **Depends on:** E8-0 · **Size:** M

**Spec sections to read first:** §11.2 (P2-1 paragraph — REQUIRED; v1.2 carries the O12 refinements), §12 (probe-interval row), §19 (item 10\)

**Context:** A paused worker makes no calls, so a traffic-driven breaker can never observe the success it needs to close — without probes, every outage ends in a manual pod restart. A scheduled synthetic call per provider, through its breaker, running regardless of pause state, is what actually recovers the system.

**Scope — build this:**

- Scheduled probe per provider executing **through the same breaker instance** as record traffic; runs in both workers (breakers are process-local); active regardless of consumer pause state; **interval 15 s while the breaker is not-CLOSED; may relax to 60 s or stop while CLOSED** (O12.2 — real traffic drives a closed breaker)  
- GitHub probe: app-JWT `GET /rate_limit` (installation-independent; ⚠ VERIFY JWT acceptance \+ that it doesn't consume rate limit — O12)  
- Anthropic probe: cheapest authenticated liveness call (models-list-style GET, no token cost — ⚠ VERIFY) using a **dedicated ops-owned probe key** from secret config — platform secret, BYOK-grade secrecy, **never used for customer reviews** (O12.3)  
- **O12.1:** probe 401/403 \= credential failure, **EXCLUDED from breaker counting** (says nothing about provider health) → `probe_credential_failure` metric feeding E10-2's dedicated page — otherwise a revoked probe key masquerades as an eternal outage and permanently pauses the fleet  
- Probe successes drive HALF\_OPEN→CLOSED (clearing the gate via E8-1); non-auth probe failures while CLOSED count into the sliding window (probes also detect outage onset under zero traffic — intended)  
- Probe-result metric per provider (success/failure)

**Out of scope:** health-check probes (E8-2 — different state machine), alert rules (E10-2)

**Invariants that apply:** none (the probes make I6's first conjunct *clearable*)

**Acceptance criteria:**

- [ ] With intake fully paused and the breaker OPEN, probes are the only outbound calls, occupy the half-open permits, and close the breaker when the provider heals — no restart, no record traffic  
- [ ] A 401-ing probe never feeds the breaker window (breaker state unchanged) but fires `probe_credential_failure`; a missing probe key fails startup loudly  
- [ ] **Tests:** unit — scheduling active under pause; interval policy by breaker state; WireMock — OPEN \+ healthy provider ⇒ HALF\_OPEN probes ⇒ CLOSED within \~2 intervals, gate cleared; non-auth probe failures accumulate in the window while CLOSED; 401 probe ⇒ excluded-from-window asserted \+ metric; missing-key startup failure; log-capture: no credential material  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the probe must go *through* the breaker, not around it — a probe that pings the provider directly and force-transitions the breaker is a different (wrong) design; Resilience4j closes on observed successes, and that observation is the mechanism

---

### **\[ \] \[E8-4\] Outage integration test**

**Epic:** E8 · **Depends on:** E8-1, E8-2, E8-3, E5-10 · **Size:** L

**Spec sections to read first:** §18 (breaker \+ breaker-recovery bullets, verbatim), §11 (organizing rule), §7.2 (`BREAKER_PARKED` row), §17 (rows 16, 20, 21, 29\)

**Context:** The degraded-mode story proven whole on the E5-10/E6 bed: outage ⇒ pause with frozen counters and a bounded parked remnant; recovery ⇒ automatic drain **without pod restart** (the P2-1 assertion). Kafka's log is the waiting room; the test proves movement stopped being heat and became progress again on its own.

**Scope — build this (Testcontainers \+ WireMock; ms backoffs; probe interval shrunk via config):**

- **GitHub 503-storm full cycle (§18 \+ row 16):** backlog flowing ⇒ sustained 503 ⇒ breaker OPEN ⇒ both workers pause (`pause_state` reasons asserted); in-flight remnant exits `BREAKER_PARKED` to retry with `not_before ≈ +60 s` and **zero counter changes from trip time** (row-level before/after snapshots); zero new DLQ entries during the outage ⇒ flip healthy ⇒ **probes close the breaker, intake resumes, backlog drains to `SUCCEEDED` — no restart of any component** (row 29\)  
- **Anthropic outage variant:** same shape tripping `anthropic` mid-pipeline — remnant path from a later step \+ independent breaker identity  
- **Postgres down/up (row 21):** stop PG ⇒ health breaker pauses workers after the anti-flap trip; gateway keeps accepting webhooks throughout (events accumulate in Kafka) ⇒ restart ⇒ resume after the anti-flap window ⇒ backlog drains; no event lost, no counter burn  
- **Redis down/up (row 20):** unified pause; resume; lossless drain  
- Every scenario asserts the §11 observable: outcomes during outage are `BREAKER_PARKED` only — never `TRANSIENT` exhaustion, never DLQ drift

**Out of scope:** Kafka-down (gateway 503 proven in E3-3; consumer-idle needs no logic), chaos/pod-kill (v2 hardening)

**Invariants that apply:** I6 — hysteresis under a real outage · I9 — remnant parks produce-then-complete · I5 — parked records terminate exactly once per hop

**Acceptance criteria:**

- [ ] All four scenarios green, non-flaky (3 consecutive runs); the no-restart property is structural (no lifecycle calls between trip and drain)  
- [ ] Frozen-counters assertion is row-level before/after from trip time; remnant size ≤ in-flight cap (asserted ≤ 200\)  
- [ ] **Tests:** this ticket is the tests; the four scenarios are the required set  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the pre-trip window legitimately increments a few `error_attempts` (5xx failures *before* the breaker opened) — the frozen-counters assertion starts at trip time; asserting zero increments across the whole scenario would fail a correct implementation

---

# **E9 — Features**

*Placement (O13, ruled): settings hosted in the gateway at `/settings/**`, installations-table slice only — no code path to `reviews`/`dlq_entries`. KMS least-privilege: gateway `kms:GenerateDataKey` only, workers `kms:Decrypt` only (IAM in E11-1). Postgres down ⇒ `/settings` 503s while webhook ingestion is unaffected.*

### **\[ \] \[E9-0\] Settings authentication: OAuth \+ installation-admin \+ signed state (D-3)**

**Epic:** E9 · **Depends on:** E3-3, E1-5 · **Size:** M

**Spec sections to read first:** §14.1 (D-3 block; v1.2 carries the O13 auth-scoping note), §19 (item 6), §17 (row 36\)

**Context:** The security wall in front of every key write: without it, an attacker enumerates `installation_id`s and overwrites victims' keys (row 36). Three parts, all mandatory: GitHub OAuth login, verification that the logged-in user has admin access to *this* installation, and a signed `state` parameter so the post-install redirect can't be forged or replayed onto another installation.

**Scope — build this:**

- GitHub App **Setup URL** handler in the gateway: receives `installation_id` \+ `setup_action` → HMAC-signed `state` binding `{installation_id, nonce, expiry ≤ 10 min}` (app secret; `MessageDigest.isEqual` on verify) → starts OAuth  
- OAuth: the GitHub App's user-authorization flow (client id/secret from secret config, O6-grade secrecy); server-side code exchange; the user token is used **only** to verify installation access, then discarded — session \= signed, HttpOnly, short-lived cookie scoped to the verified `installation_id`  
- Admin verification: the authenticated user must have admin rights over the installation's account (⚠ VERIFY exact endpoint); failure → 403, no session  
- Every `/settings/**` route behind the session filter \+ **CSRF protection on form POSTs \+ basic rate limit** (O13.2); expired/foreign-installation sessions rejected; a login route supports direct navigation with a fresh signed state

**Out of scope:** the settings form (E9-1), `/admin/**` (different surface/auth — E7-1), token persistence of any kind

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Tampered/expired `state`, a replayed `state` bound to another installation, or a non-admin user all fail closed (403), no session issued  
- [ ] **Tests:** unit — state sign/verify matrix (valid, per-field tamper, expired, wrong installation, replay-nonce), constant-time compare; WireMock GitHub — code exchange, admin-verified happy path issues scoped session, non-admin 403, foreign-installation session rejected on use; CSRF-missing POST rejected; log-capture — no OAuth secrets/tokens  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.6 applies to the `state` HMAC; the classic hole is verifying "logged in" without binding the session to a *specific verified* `installation_id` — the enumeration attack walks through that gap

---

### **\[ \] \[E9-1\] Settings page: key validation, storage, rotation, model**

**Epic:** E9 · **Depends on:** E9-0, E5-3, E5-0, E5-10 · **Size:** M

**Spec sections to read first:** §14.1 (flow \+ encryption-assembly cross-ref), §5.1 (`installations`), §12 (O12 validation-call pattern)

**Context:** The app is inert without keys — this is onboarding. Paste key → validate with a cheap live call → envelope-encrypt via E5-3 → store per installation; same page rotates keys and sets `default_model`. The page's own logging is a leak surface the spec calls out: redact request bodies and validation-failure messages.

**Scope — build this:**

- Minimal server-rendered form behind E9-0's session: masked key field, `default_model` select from a config-driven allowlist (E1-5; S-6's principle), status display (key set yes/no \+ `key_version`, never key material)  
- Validate on submit: one cheapest-authenticated Anthropic call with the pasted key (O12 pattern, ⚠ VERIFY); invalid → generic failure message (no upstream body echoed), nothing stored  
- Valid → `KeyCryptoService.encrypt` (E5-3; gateway exercises **only** the encrypt half — O13.1) → upsert `installations` `{S-5 fields, default_model}` (write side added to E5-0's repo here); rotation \= same path, new `key_version`  
- Redaction hardening: request-body logging disabled on `/settings/**`; exception handlers scrub messages; the pasted key exists only in request scope  
- Model-only edit supported

**Out of scope:** `.reviewbot.yml` interplay (repo override already wins, E5-6), key deletion/offboarding (deferred — installation-lifecycle webhooks), styling beyond usable

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] End-to-end: paste valid key → validated → stored encrypted (all four S-5 fields) → next pipeline run for that installation decrypts and reviews (asserted on the E5-10 bed reusing the row)  
- [ ] Invalid key stores nothing; rotation replaces ciphertext and bumps `key_version`; model-only edit touches no key fields  
- [ ] **Tests:** WireMock Anthropic — valid/invalid validation call; Testcontainers PG — upsert/rotation/model-only matrix with S-5 field assertions; disallowed model rejected; log-capture across submit \+ failure paths: no key material anywhere (the mandatory test); unauthenticated/foreign-session → 403 on the real routes  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** §19.7 — keys never logged, decrypted only at point of use; the sneaky variant is the *validation-failure* path echoing the provider's 401 body (which can quote the key) — the generic-message rule exists for exactly that

---

### **\[ \] \[E9-2\] `.reviewbot.yml` loader \+ `ghcfg` cache**

**Epic:** E9 · **Depends on:** E5-6, E5-1, E5-2, E8-0 · **Size:** M

**Spec sections to read first:** §7.1 (step 7), §5.2 (`ghcfg` row \+ never-source-of-truth paragraph), §14.3

**Context:** Activates real per-repo config through the `RepoConfigSource` seam E5-6 left — pipeline code untouched, defaults become the fallback they were always meant to be. The cache exists so config costs one contents-API call per repo per 5 minutes, not one per event.

**Scope — build this:**

- `getContents` operation added to the E5-1 client (default branch, `.reviewbot.yml`), breaker-decorated — E8-0's decorated-coverage test extended  
- Loader: `ghcfg:{inst}:{repo}` hit → parsed `RepoConfig` JSON, done · miss → contents fetch → E1-5 lenient parse (404 ⇒ defaults; parse error ⇒ defaults \+ one log per fetch) → cache the **resolved effective config** (negative results included) `EX 5 min` best-effort → return  
- Redis miss/down ⇒ fail open to direct fetch (§5.2 cache doctrine; the P1-4 blanket covers ops *without* a specified fallback — this one has one; pre-trip window only in practice, E8-2 pauses shortly after Redis truly dies)  
- Contents-call failures route through the classifier: 5xx/timeout ⇒ `TRANSIENT`, 429 ⇒ `DEFER`, breaker ⇒ `BREAKER_PARKED` — a config fetch failure defers the review rather than silently reviewing with wrong settings (**only 404 means "no file" ⇒ defaults**)  
- Seam swap: `RepoConfigSource` bean \= loader; E5-6's default-source remains as test/fallback implementation

**Out of scope:** new config keys (`review_drafts` stays gateway-side per O4), cache invalidation beyond TTL

**Invariants that apply:** I5 — fetch-failure paths resolve to exactly one outcome like any pipeline step

**Acceptance criteria:**

- [ ] A repo with valid yml gets `min_severity`/`model`/`review_authors` applied on the next uncached event; broken yml reviews with defaults and logs once  
- [ ] Exactly one contents call per repo per TTL window under bursts (WireMock request-count)  
- [ ] **Tests:** WireMock \+ Testcontainers Redis — hit/miss/negative-cache/TTL-expiry matrix with request counts; parse-error ⇒ defaults \+ single log; Redis stopped ⇒ direct fetch works; 5xx ⇒ `TRANSIENT` (deferred, not default-reviewed); 404 ⇒ defaults reviewed; seam-swap integration: pipeline picks up loaded config with zero pipeline-code diff  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the wrong-settings failure mode — treating a *transient* fetch failure like a *missing file* silently reviews with defaults (e.g. leaking a raised `min_severity`); only 404 means defaults

---

### **\[ \] \[E9-3\] Incremental review (§7.8)**

**Epic:** E9 · **Depends on:** E5-6, E5-1, E5-0, E5-10 · **Size:** L

**Spec sections to read first:** §7.1 (step 8, complete), §14.3, §5.1 (`idx_reviews_last`), §19 (item 10\)

**Context:** The headline feature: review only what changed since the last successful review. It slots in *before* E5-6's degenerate branch — which becomes exactly what it was designed to be: the no-base and diverged fallback. Semantics over savings: after a force-push/rebase, full-diff is *correct*, not merely safe.

**Scope — build this:**

- Base lookup in E5-0's repo: latest `SUCCEEDED` for `(i,r,p)` with `head_sha ≠ current`, via the `idx_reviews_last` partial index (`completed_at DESC`) — no round qualifier, latest wins  
- `compareCommits(base...current)` added to the E5-1 client, breaker-decorated (+ coverage-test extension); failures through the classifier  
- Decision per §7.8: no base ⇒ existing full-diff path · `status ∈ {ahead, identical}` ⇒ delta `Diff` from compare patches, **with changed-file context**: full content of each changed file at `current` (bounded contents calls) carried in `ReviewContext` — the documented middle setting · `diverged` or compare error-status ⇒ full-diff fallback (`identical` ⇒ empty delta flows normally — zero findings, one-liner)  
- Size-guard input (E5-6 step 9\) \= delta \+ context total — the estimate covers everything prompt-bound  
- ⚠ VERIFY: compare-API status values; comment-only note on the `synchronize` force-push signal as the documented cheaper alternative — not built in v1  
- Prompt framing: one-line instruction that hunks are the change-under-review and file contents are context — inside the existing S-3 fence

**Out of scope:** chunking (v2), compare-result caching, per-file context truncation heuristics (the size guard is the only guard)

**Invariants that apply:** I5

**Acceptance criteria:**

- [ ] Second push triggers compare-based review (WireMock asserts compare \+ per-changed-file contents calls, and *no* full-PR files listing); first-ever review takes the full-diff path  
- [ ] Force-push (`diverged`) falls back to full diff; compare 5xx defers as `TRANSIENT` rather than falling back (a *transient* error is not history-rewrite evidence — only `diverged`/error-status is)  
- [ ] **Tests:** Testcontainers PG — base-query matrix (no base; one; multiple → latest; same-SHA excluded) with the partial index serving it; WireMock — ahead/identical/diverged/404/5xx matrix with path assertions; size-guard-over-delta+context boundary; integration on the E5-10 bed — full review → push → incremental review posts with correct marker for the new SHA; golden prompt: delta as change, contents as context, all inside the fence  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the fallback-direction trap cuts both ways — `diverged` must *not* be treated as an error (semantic signal → full diff), and a transient error must *not* be treated as `diverged` (retryable → `TRANSIENT`); collapsing either way breaks correctness or the retry budget

---

# **E10 — Observability**

### **\[ \] \[E10-0\] Telemetry completion: remaining metrics, traces, JSON logs**

**Epic:** E10 · **Depends on:** E5-9b, E6-1, E7-0, E8-2 · **Size:** L — *pre-authorized in-flight split at the metrics | traces+logs seam if the session runs long*

**Spec sections to read first:** §15 (entire — the metrics list is the checklist), §12 (stale-PROCESSING row), §10.2 (S-4), §3.2 (`trace_id`)

**Context:** Closes the gap between "metrics exist where built" and "§15's list is fully live," and wires the two non-metric §15 items: OTel traces correlated by the `trace_id` already riding every Kafka header, and structured JSON logging with S-4 enforced, not just stated. The one engine touch (watermark lag) is a read-only snapshot — the 100%-branch gate still applies.

**Scope — build this:**

- **Missing metrics:** consumer lag per partition for all three groups (Micrometer Kafka binding) · watermark lag per partition (highest-routed − watermark; poller tracks highest-routed, tracker exposes watermark snapshot — read-only, no invariant-bearing path altered) · LLM latency timer \+ input/output tokens (API usage fields) \+ cost estimate (tokens × config price map, defaults marked approximate) in E5-3 · lock-acquisition-failure counters (held / redis-down) in E5-2 · stale-PROCESSING age gauge (scheduled query: oldest `PROCESSING` row's `updated_at` age)  
- **Traces:** OTel SDK in all three deployables; gateway starts/continues the trace and sets the `trace_id` header (E3-3's field becomes a real trace id); workers adopt it — spans around `processReview` and each external call via the existing seams; `trace_id` into MDC  
- **Structured JSON logs:** logback JSON encoder everywhere, MDC fields (`trace_id`, installation, repo, pr); **S-4 enforcement:** log-scrub test utilities asserting no diff content, no prompt content, no key material across representative flows — sizes and hashes only  
- **Metrics inventory file** (machine-readable, `infra/observability/`): every §15 metric name \+ labels — the golden source for E10-1's drift check and this ticket's presence test

**Out of scope:** dashboards (E10-1), alert rules (E10-2), sampling/retention tuning (deploy-time)

**Invariants that apply:** I3/I4 — untouched; the tracker/poller additions are snapshots, proven by existing property tests still passing \+ branch coverage on new accessors

**Acceptance criteria:**

- [ ] Scrape endpoints on all three deployables expose every inventory metric with exact names/labels; the presence test is inventory-driven  
- [ ] A webhook's `trace_id` is observable end-to-end: gateway span → header → worker span → DLQ envelope field (drive one event to DLQ, assert the envelope's `trace_id` matches)  
- [ ] **Tests:** inventory-presence per deployable; trace-propagation integration (happy \+ DLQ paths); adapter token/cost metrics from WireMock usage fields; stale-PROCESSING gauge vs a seeded old row; watermark-lag unit tests (engine gate); S-4 log-scrub on happy, failure, and settings paths  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** S-4 is the trap — JSON logging makes it *easier* to serialize a payload object into a log field; the scrub tests exist because the encoder change is exactly when this regresses. §19.13 — `trace_id` via the E1-0 codec like every header.

---

### **\[ \] \[E10-1\] Grafana dashboards as code**

**Epic:** E10 · **Depends on:** E10-0, E2-2 · **Size:** M

**Spec sections to read first:** §15 (metrics list — panels mirror it), §2 (`infra/` dashboards line), §16 (table only — which measured triggers deserve a panel; documentation, not scaling work)

**Context:** Dashboards live in `infra/dashboards/` as provisioned JSON — reviewable, versioned, drift-checked against the E10-0 inventory. A local observability profile makes them real. §16's "trigger (measured)" column tells us which panels earn a place: they're the numbers the scaling runbook reads.

**Scope — build this:**

- Compose `observability` profile: Prometheus (scraping gateway \+ both workers) \+ Grafana (datasource \+ dashboard provisioning) — off by default; `verify-stack.sh` gains a profile-aware check  
- Five boards, panels named against the inventory: **Overview** (lag ×3 groups, in\_flight, pause\_state by reason, outcomes rate) · **Pipeline** (outcomes by class, watermark lag, retry\_delay\_excess p95, stale-PROCESSING age, lock failures) · **DLQ** (depth/NEW by class, indexer lag, indexed rate) · **Providers** (breaker states, rate-limit remaining, LLM latency/tokens/cost, probe results) · **Gateway** (webhook latency, non-2xx, dedup hits/fail-open, settings 5xx separated from webhook 5xx per O13.3)  
- Drift-check script in CI: every panel query references an inventory metric; unknown ⇒ fail  
- §16-trigger annotations: panels backing a documented scale trigger carry a note naming it (e.g. `retry_delay_excess` → "trip-wire: split topics at p95 \> 60 s")

**Out of scope:** alert rules (E10-2), Grafana in production (E11-2 pointer; JSON is portable), new metrics

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] `docker compose --profile observability up` yields Grafana with all five boards loaded and populated while the E5-10 bed (or manual smoke) drives traffic  
- [ ] **Tests:** JSON validity \+ provisioning-loads smoke (Grafana API lists all five, zero load errors) in a CI compose job; drift-check green, and demonstrably red when a panel references a fake metric (verified once, reverted); `verify-stack.sh` profile check  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the failure mode is decorative panels; the inventory drift-check and §16-trigger annotations keep these boards operational rather than ornamental

---

### **\[ \] \[E10-2\] Alert rules**

**Epic:** E10 · **Depends on:** E10-0 · **Size:** M

**Spec sections to read first:** §15 (alerts paragraph, verbatim; v1.2 carries the O14 principle/splits/thresholds), §12 (threshold rows), §17 (rows 24, 37\)

**Context:** Prometheus rules as code with promtool unit tests. Sorting principle (O14): **page \= system/platform broken, operator acts now; warn \= tenant/input problem that self-serves or waits.** The P2-5 class dimension is the triage story: a second tenant's entry must never hide behind a known flood. The P2-4 outcome-rate alert is the fast bad-deploy tripwire (\~60 s, not \~21 min via DLQ depth).

**Scope — build this (`infra/observability/` rules YAML, per-rule promtool tests):**

- DLQ depth \> 0 for 5 min, **dimensioned by class** (P2-5, O14): `page` classes \= `POISON_DESERIALIZATION`, `EXHAUSTED_*`; `warn` classes \= `PR_NOT_FOUND`, `INSTALLATION_REVOKED`, `MISSING_OR_INVALID_API_KEY`, `DIFF_TOO_LARGE`; **`COULD_NOT_SCHEDULE` \= warn per-entry, escalating to page at count \> 10 over 1 h**; **`LLM_REQUEST` \= page at volume \> 2 in 1 h** (tolerates lone estimator misses until v2 chunking); lists in one config block, marked ops-tunable with the two-week review note  
- Any provider breaker OPEN → page · Postgres/Redis health-unhealthy → page  
- Outcome-rate (P2-4, O14): SUCCESS-ratio \< 50% over 5 min with ≥ 20-outcome floor → page; `TERMINAL+TRANSIENT` spike: **current \> max(3 × 1 h-baseline, 5 absolute)** → page (baseline floor prevents first-failures-after-quiet paging on a \~0 baseline)  
- Main-topic lag growing 10 min → warn · `retry_delay_excess` p95 \> 60 s → warn (annotation: "Approach B step 1 — split defer/error topics") · stale-PROCESSING \> 10 min → warn · `dlq_indexer_lag` sustained → warn (V-4) · gateway 5xx → warn (webhook path; settings 5xx separate warn per O13.3)  
- Ruling-added: `probe_credential_failure` → "probe credentials broken" page (O12.1 — only this alert sees probe-auth failures) · `handler_produce_failure` \> 0 → page (V-2 — intake is stalling by design; a human must know)  
- Every breaker/health transition → info-level alert-feed event (§11.2)

**Out of scope:** alert routing/receivers (E11-2 runbook), threshold retuning, new metrics

**Invariants that apply:** none

**Acceptance criteria:**

- [ ] Every §15 alert exists, traces to it in a comment, and carries a runbook-pointer annotation (E11-2 anchors them)  
- [ ] Class-dimension behavior proven: a known-flood class firing `warn` while an unexpected class *simultaneously* fires `page` (the masking scenario, tested directly); the `COULD_NOT_SCHEDULE` escalation and `LLM_REQUEST` volume floor both fire/no-fire correctly  
- [ ] **Tests:** promtool unit tests per rule — fire and no-fire series, incl. the P2-4 ratio with volume floor (no page on 3 events), the spike baseline floor, the 5-min DLQ persistence window, the simultaneous-class scenario, probe-credential fire, `handler_produce_failure` fire; CI runs promtool  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** P2-5's own warning encoded — a class-blind scalar re-pages through triage and masks the second tenant; the simultaneous-class test exists so no future "simplification" to a scalar can pass

---

# **E11 — Deploy**

### **\[ \] \[E11-0\] Container images \+ EKS manifests**

**Epic:** E11 · **Depends on:** E2-1, E9-1, E10-0 · **Size:** L

**Spec sections to read first:** §2 (deployables \+ `infra/` layout), §4.2 (per-app client configs — env-driven), §12 (shutdown-grace row), O1/O13 rulings (v1.2 §2/§14.1), §11.2 (pause ≠ unhealthy)

**Context:** Three images, one Kustomize tree, and the operational decisions that make the design's guarantees survive contact with k8s: probes that don't kill paused-but-healthy workers, `terminationGracePeriodSeconds` that honors the drain, a migration Job so schema always precedes code (O1), and network policy standing in front of `/admin/**` and `/settings/**` scoping.

**Scope — build this:**

- Dockerfiles per deployable: multi-stage Maven build, JRE 21 runtime, non-root user, `SIGTERM`\-clean entrypoint (no shell-wrapped PID 1); CI job builds all three (push on main; registry parameterized)  
- Kustomize base \+ overlay (`infra/k8s/`): Deployments — gateway ×2 behind a Service \+ Ingress (`/webhook` \+ `/settings/**` exposed); review-worker ×2, retry-worker ×1, **no HPA, fixed replicas** (per constraints; §16 documents triggers, v1 doesn't automate them)  
- Probes: liveness \= process-level health only; readiness — gateway \= HTTP up; workers \= **started/live, explicitly independent of `pause_state`** (§11.2: a paused worker is healthy and must not be restarted or de-endpointed into a crash loop); comment in the manifest states this  
- `terminationGracePeriodSeconds` \> shutdown drain grace (30 s \+ margin, e.g. 45 s) so E4-4's drain always completes; `preStop` sleep for gateway connection draining  
- **Migration Job (O1):** Flyway container running `infra/db/migration` against RDS, wired as the documented pre-rollout step (Kustomize component \+ runbook cross-ref); apps never self-migrate in prod  
- Config/Secrets: env-driven §4.2/§12 values via ConfigMap; secrets (webhook secret, app PEM, OAuth client secret, probe key, DB/Redis creds) via ExternalSecrets-or-sealed pattern (pick one, document); **IRSA service accounts per O13.1 KMS split** — gateway SA ↔ encrypt-only role, worker SA ↔ decrypt-only role (roles from E11-1)  
- **NetworkPolicy:** `/admin/**` reachable only in-cluster (worker admin port not on any Ingress); default-deny egress with allowlist (GitHub, Anthropic, RDS, ElastiCache, MSK, KMS)  
- Resource requests/limits per deployable (modest, \~10 ev/s), JVM container-aware flags

**Out of scope:** AWS resource provisioning (E11-1), runbook text (E11-2), Grafana hosting (JSON is portable — E11-2 pointer), HPA/scaling

**Invariants that apply:** I7 — the grace-period setting is what lets the shutdown protocol actually run under k8s eviction

**Acceptance criteria:**

- [ ] `kustomize build` renders clean for base \+ overlay; kubeconform (or equivalent) green in CI  
- [ ] All three images run as non-root and propagate `SIGTERM` to the JVM (verified: `docker stop` → clean E4-4 shutdown log line locally)  
- [ ] Manifest assertions scripted in CI: worker readiness probes reference no pause-derived endpoint; `terminationGracePeriodSeconds` ≥ drain grace \+ margin; every secret consumed by the deployments exists in the ExternalSecrets manifests; SA↔role annotations match the O13 split; no HPA object present  
- [ ] **Tests:** the CI validation suite above (kustomize render, kubeconform, image SIGTERM check, manifest-assertion script) — validation-style by nature; the behavioral proof is E11-2's cutover smoke  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the readiness-vs-pause trap is the named one (§11.2): wiring readiness to anything derived from `pause_state` turns every provider outage into a rolling-restart storm — the manifest comment plus the CI assertion keep it from regressing

---

### **\[ \] \[E11-1\] AWS foundation (Terraform)**

**Epic:** E11 · **Depends on:** E2-4 · **Size:** L

**Spec sections to read first:** §2 (AWS inventory), §4.1/§4.2 (broker-side expectations: RF/ISR), §14.1 (KMS \+ O13.1 split), O12 (probe-key secrecy class)

**Context:** The real infrastructure under the manifests: MSK, RDS, ElastiCache, KMS, IAM — as Terraform in `infra/terraform/`, minimal but production-shaped. The one correctness-critical piece is IAM: the KMS least-privilege split is a *designed control* (O13), so the roles are asserted, not assumed.

**Scope — build this:**

- MSK: 3 brokers across 3 AZs, RF=3 / `min.insync.replicas=2` cluster defaults consistent with §4.2; TLS in-transit; the E2-4 provisioning script executed against MSK as the documented topic-creation step (same script, prod parameters — RF/ISR asserted by the script's `--describe` checks)  
- RDS PostgreSQL 16: multi-AZ, private subnets, automated backups \+ deletion protection; parameter group with sane connection limits for 5 pods  
- ElastiCache Redis 7: single node (v1 scale; §5.2's cache/coordination posture tolerates node loss — locks and dedup degrade per design), private subnets  
- KMS: one CMK for BYOK envelope encryption; **two IAM roles for IRSA** — `reviewbot-gateway` (`kms:GenerateDataKey` only) and `reviewbot-worker` (`kms:Decrypt` only), both key-scoped; OIDC provider wiring for the EKS cluster (cluster itself: minimal managed node group, or document bring-your-own-cluster — decide once, in-repo)  
- Security groups: pods→RDS/ElastiCache/MSK on service ports only; nothing public except the gateway's load balancer  
- Secrets Manager entries for E11-0's ExternalSecrets (webhook secret, PEM, OAuth secret, probe key — O12/O6 secrecy classes noted in descriptions)  
- Remote state \+ `terraform fmt`/`validate` in CI; cost note in `infra/terraform/README.md` (this stack costs real money idle; teardown instructions included)

**Out of scope:** k8s objects (E11-0), Grafana/Prometheus hosting (E11-2 decision), multi-env promotion (single `prod` workspace; overlay pattern documented)

**Invariants that apply:** none directly (RF/ISR settings are what make the §4.2 producer guarantees real in prod)

**Acceptance criteria:**

- [ ] `terraform validate` \+ `plan` clean in CI (fmt-checked); `tflint`/policy lint green  
- [ ] **Live verification checklist** (scripted where possible, run once at apply, results recorded in PROGRESS.md): E2-4 script against MSK shows all three topics at RF=3/ISR=2; gateway role denied `kms:Decrypt` and worker role denied `kms:GenerateDataKey` (policy simulation or live negative test — the O13 assertion); RDS reachable only from pod SGs; every Secrets Manager entry present  
- [ ] **Tests:** the CI lint/validate suite \+ the scripted apply-time checklist — validation-style by nature; behavioral proof lands in E11-2's cutover  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the KMS split is easy to erode with a convenience `kms:*` policy during debugging — the negative-permission check exists so the designed control is verified as *absent* capability, not just present policy text

---

### **\[ \] \[E11-2\] Runbook, cutover checklist \+ README**

**Epic:** E11 · **Depends on:** E11-0, E11-1, E10-2 · **Size:** M

**Spec sections to read first:** §4.4 (runbook items), §10.3 (playbook \+ O10 line), §6.1 (redelivery sweep), §11 (pause semantics for operators), §13.4 (residual statement — verbatim source), O1 (migration procedure)

**Context:** The operator-facing close-out: every alert lands on a procedure, every §4.4/§10.3 "documented, not built" item gets its page, cutover is a checklist not an adventure — plus the minimal README (ruled): one architecture paragraph and the §13.4 residual statement verbatim, so the repo states its own posture honestly.

**Scope — build this (`docs/runbook.md`, `docs/cutover.md`, `README.md`):**

- **Runbook, one anchor per E10-2 alert** (link-checked): breaker-OPEN pages (what pause looks like, why *not* to restart — **restarting clears breaker state and destroys the recovery evidence; probes close breakers, pods don't** — what to check: provider status, probe metrics); health pages (PG/Redis triage, anti-flap expectations O11); DLQ pages by class incl. the **O10 poison playbook: replay one entry to confirm the fix, then the rest**; `COULD_NOT_SCHEDULE` escalation (capacity vs throttle triage via `retry_delay_excess`); `probe_credential_failure` (rotate the ops key — O12); `handler_produce_failure` (Kafka produce health — intake is deliberately stalled); stale-PROCESSING (zombie triage: §5.1 stale-claim semantics do the recovery; when to intervene)  
- **Procedures:** redelivery sweep after gateway outage (§6.1: GitHub UI/API redelivery per installation, dedup absorbs overlap — ⚠ VERIFY API endpoints \+ retention window); topic-migration path (§4.4 rule 3: new topic \+ dual-write — documented, never `--alter`); schema migration (O1: run the Job, verify `flyway_schema_history`, then roll deployments); secret rotation per class (webhook secret, PEM, OAuth, probe key, BYOK CMK implications); scale triggers table lifted from §16 with "where to look" per row  
- **Cutover checklist:** ordered — Terraform apply \+ live checklist (E11-1) → migration Job → deploy → GitHub App creation/config (permissions: PR read/write, contents read, webhook URL \+ secret, Setup URL for E9-0 — ⚠ VERIFY exact permission set) → smee/prod webhook swap → install on a test repo → settings page onboarding with a real key → PR → **recorded first review** (the cutover smoke: link the PR in PROGRESS.md) → alert-route sanity (fire one test alert)  
- **README.md (ruled):** one paragraph — what reviewbot is, the architecture in five sentences (gateway → Kafka → parallel-consumer workers → Postgres/Redis → BYOK Anthropic), pointer to spec v1.2, runbook, and this backlog; then the **§13.4 residual statement verbatim** under "Known residual risk"  
- Alert-rule annotations (E10-2) updated to the final anchor URLs if paths shifted

**Out of scope:** any new code; Grafana hosting decision documented as local-JSON-portable (one line); marketing polish

**Invariants that apply:** none (the runbook is where I6/I7's operational meaning gets written down for humans)

**Acceptance criteria:**

- [ ] Link-check in CI: every E10-2 alert annotation resolves to an existing runbook anchor; no orphan anchors  
- [ ] Cutover executed end-to-end once: the recorded first production review exists and is linked in PROGRESS.md; every ⚠ VERIFY in the docs is resolved-or-ticketed (none silently dropped)  
- [ ] README contains the §13.4 statement **verbatim** (diff-checked against spec v1.2 in CI — a paraphrase is a drift bug)  
- [ ] **Tests:** the link-check \+ README-verbatim CI checks \+ the recorded cutover smoke — validation-style by nature; the smoke is the system's first behavioral proof in production  
- [ ] build green, lint clean, PROGRESS.md updated

**Known traps:** the restart-reflex is the one the runbook must actively fight — §11's design makes patience correct (probes recover breakers, stale-claim recovers zombies), and an operator page that doesn't say "don't restart" will be read as "restart"

---

# **Deferred — not in this backlog**

Everything below is designed (spec v1.2) but explicitly out of V1 scope. Do not build opportunistically; each returns as its own workstream with its own tickets.

**v1.5 — comment commands:** `/rereview` (force branch of the claim — E5-0 rejects `force=true` loudly today), `/model` override command, advisory `COMMENT` interplay, command envelope types (§3.1 reserves them; E1-0/E4-1 are shaped for additive arrival), the §19.8 bot-loop guard (comment-event-only — v1 accepts no comment events, so no loop exists).

**v2:** fence-token concurrency upgrade (`reviews.fence` column already shipped in V1 schema, unused), reconciliation-before-LLM \+ find-or-create posting (§13.3 beyond the v1 guard read), orphan-cleanup sweep, diff chunking for oversized PRs (v1: `DIFF_TOO_LARGE` terminal \+ O8 guard), Redis per-role fail-open flag (§11.1 one-switch footnote; comment-only slot in E8-2), container-kill chaos testing (v1: in-JVM fault injection only).

**Deferred hardening (spec §20 list, minus what V1 pulled in):** per-installation kill switch / `SUSPENDED` enforcement (`installations.suspended` column \+ E1-2 vocabulary exist; no v1 pipeline step reads it — §17 row 35), flood-aware DLQ bulk operations (§10.3 bulk replay/discard endpoints), 30-day DLQ purge job (S-4 retention), per-repo `review_drafts` opt-in (O4 keeps it gateway-global), installation-lifecycle webhooks (uninstall/suspend handling \+ key offboarding), prompt-injection posture beyond S-3 fencing+defang, breaker threshold tuning under real traffic (O14's two-week review note is the entry point), health-check breaker tuning beyond O11 defaults.

*Pulled into V1 by ruling (for the record): P2-1 recovery probes (E8-3), settings OAuth D-3 (E9-0), BYOK key-crypto both halves (E5-3), README with the §13.4 statement (E11-2).*

---

# **Constants & rulings (O1–O14) — quick reference**

All fourteen questions raised during decomposition were ruled and are baked into **spec v1.2** (source of truth) and the affected tickets (local copies). On any perceived conflict, spec v1.2 wins.

| \# | Ruling (one line) | Spec home | Tickets |
| :---- | :---- | :---- | :---- |
| O1 | Flyway: app-startup locally; dedicated k8s migration Job before rollout | §2 | E2-3, E11-0, E11-2 |
| O2 | Envelope validation per-type; §3.1 fields required except defaulted `force`/`replay`; `schema_version`\=1; `head_sha` required for `REVIEW_REQUESTED` only | §3.3 | E1-0 |
| O3 | `body_snippet` cap 2048 chars \+ literal `…[truncated]` | §12 | E1-3, E1-5 |
| O4 | `review_drafts` \= gateway app-level config, default false; per-repo opt-in deferred | §6.1 | E3-1 |
| O5 | `register(tp, position)` on assign, watermark \= position−1; zombie `markComplete` \= accounting no-op that still releases the permit; unregister after revocation commitSync; dirty-tracked `committable()` | §8.4 | E4-0, E4-3, E4-4 |
| O6 | GitHub App auth: PEM at BYOK-grade secrecy; cached installation tokens w/ \~5-min early refresh; exchange-401 \= credential alert; refresh-proof 401/403 \= `INSTALLATION_REVOKED` | §6.2 (new) | E5-1 |
| O7 | Author-permission cache: in-memory per-worker, 5 min TTL, bounded — not Redis | §7.1/§12 | E5-4 |
| O8 | Size guard: chars/4 estimate; per-model budget map default 150 000; trips \> 0.9 × budget (⚠ VERIFY windows) | §12 | E5-6 |
| O9 | Bot login: config \+ startup verification vs App identity endpoint, fail-fast, process-lifetime cache | §13.1 | E5-8 |
| O10 | RAW envelope gains `original_key_b64` \+ `original_headers` (captured at envelope build in the worker; indexer persists); RAW replay \= verbatim bytes \+ originals \+ `dlq_replay=true`, no `replay` flag; playbook: one poison entry first | §10.2/§10.3 | E1-3, E7-0, E7-1, E7-2, E11-2 |
| O11 | Health resume: 3 consecutive successes AND span ≥ 30 s; failure resets both; span starts at first success after last failure; cadence 5 s | §12 | E8-2 |
| O12 | Probes: GitHub app-JWT `/rate_limit`; dedicated ops probe key (never for customer reviews); probe 401/403 excluded from breaker → `probe_credential_failure` page; 15 s while not-CLOSED, relax/stop while CLOSED | §11.2 | E8-3, E9-1, E10-2 |
| O13 | Settings in gateway `/settings/**`, installations-slice only; KMS split — gateway `GenerateDataKey`, workers `Decrypt`; HMAC scoped to `/webhook`; settings gets OAuth+CSRF+rate-limit; PG-down → settings 503s, webhooks unaffected | §2/§14.1 | E3-0, E9-0, E9-1, E11-0, E11-1 |
| O14 | Alerts: page \= system broken, warn \= tenant problem; `COULD_NOT_SCHEDULE` warn→page \> 10/1h; `LLM_REQUEST` page \> 2/1h; SUCCESS \< 50%/5 min (≥ 20 floor); spike \> max(3× 1h baseline, 5); ops-tunable, review after two weeks | §15 | E10-2 |

# **Open questions**

**None outstanding.** All questions raised across Passes 1–3 were ruled (O1–O14 above) and folded into spec v1.2. Remaining unknowns are marked **⚠ VERIFY** inside their tickets — they are implementation-time fact checks against live APIs (token lifetimes, endpoint shapes, model context windows, GitHub permission sets), not design decisions; each is resolved in its ticket's session and recorded in PROGRESS.md.

