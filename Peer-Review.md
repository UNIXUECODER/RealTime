# RealTime — External Review Findings

**Date:** 2026-09-15 · **Status:** open, awaiting triage · **Reviewer:** external (Arena agent session)
**Covers:** full codebase at `3d7c0a2` (post-M6b) + all planning docs
**Companion to:** `realtime-architecture-spec.md` (§14), `realtime-roadmap.md`

This is the backlog of everything the reviews surfaced that the existing docs either
miss, under-scope, or lost between milestones — each item with a concrete suggested
fix, an effort estimate, and a recommended milestone. It is a *triage input*, not a
second roadmap: once an item is accepted, it should move into the roadmap milestone
entry and/or a spec §14 row (the "Docs to update" line on each item says where), and
be marked done here with a commit reference. Items already covered by the roadmap are
marked as such — they appear here only where the review adds priority or scope
clarification.

Severity legend: **Critical** = outage / data-loss / security-exploitable under
realistic conditions · **High** = correctness gap or production-breaking at deploy
time · **Medium** = hardening / design work with a clear owner · **Low** = polish,
process, docs.

## Index

| ID | Title | Sev | Milestone | Status |
|----|-------|-----|-----------|--------|
| F-01 | `MAXLEN` safety cap never built — Redis OOM vector | Critical | M6c (proposed) | Open |
| F-02 | Replay API unbounded — one request can OOM the server | Critical | M6c | Open |
| F-03 | No API key rotation; `revoked_at` is dead schema | Critical | M8 | Open |
| F-04 | Auth endpoints unthrottled (bcrypt CPU DoS + brute force) | Critical | M8, first | Open — already in M8, priority raised |
| F-05 | Shared Lettuce connection + `BLOCK` XREAD head-of-line risk | Critical | Verify pre-M7; fix M8/M10 | Open — needs verification |
| F-06 | WS resume has no Postgres fallback — "gap-free" ends at the hot window | High | M8 | Open |
| F-07 | `coldRange` exclusive lower bound drops same-ms events | High | M6c | Open |
| F-08 | Malformed `since`/`last_id` → 500 instead of 400 | High | M6c | Open |
| F-09 | Null filter `field`/`value` → NPE on the ingest path | High | Pre-M6c | Done (`38a4987`) |
| F-10 | Filter persistence orphaned — no milestone owns it; multi-instance incorrect | High | M8 | Open |
| F-11 | No WS heartbeat — proxies kill quiet connections in prod | High | M10 prerequisite | Open |
| F-12 | No client auto-reconnect; no server retry in the poll loop | High | Client: ≤M9 · Server: M10 prereq | Open |
| F-13 | No drain on deploy — every push drops all live connections | High | M10 prerequisite | Open |
| F-14 | Production Redis requirements undocumented (`noeviction`, persistence) | High | M10 | Open |
| F-15 | M8's HMAC item is the wrong feature — needs a scope decision | High | Before M8 starts | Open — decision, not code |
| F-16 | M7's inbound control-frame protocol is undesigned | High | M7 planning | Open — design |
| F-17 | M7 filter cost is multiplicative — needs parse-once refactor | High | M7 | Open |
| F-18 | Slow-consumer buffer + `gap` frame (spec §9) unimplemented, untracked | High | M7 | Open |
| F-19 | Frontend event list grows unbounded | Medium | With F-12 | Open |
| F-20 | No `ReplayService`/`IngestService` regression tests | High | M6c | Open |
| F-21 | WS `?token=` will land in production access logs | Medium | M8 | Open |
| F-22 | `ArchiveWriter` discards whole batch on single conflict | Medium | M8 | Open |
| F-23 | Token revocation / password-change story undocumented | Medium | M8 (`SECURITY.md`) | Open — docs |
| F-24 | Validation hardening batch (password, sizes, email, `CurrentTenant`) | Medium | M8 | Open |
| F-25 – F-33 | Low/process/polish items (compact list, §3) | Low | Various | Open |

---

## Proposed: M6c — Pre-M7 hardening batch

**Why a batch, not scattered fixes:** F-01, F-02, F-07, F-08, F-09, F-20 are all
small (XS–S), all blocking-correctness (not polish), and all cheapest to fix before
M7 builds per-connection logic on top of the same paths. M6a sets the precedent
("harden before the next layer"). Alternative is folding them into M7's entry —
acceptable, but they deserve their own exit criteria either way.

**Ships:** F-01, F-02, F-07, F-08, F-09, F-20, F-30 (doc drift), F-05 verification
(not necessarily the fix).

**Exit criteria:** burst 200k events at one channel — Redis memory stays flat;
`?since=0` on a 1M-row archive returns a capped page without OOM; malformed `since`
and null filter fields return 400; same-ms cold-replay regression tests pass;
idle-WS-vs-ingest-latency test recorded (fix scheduled or ruled out).

**Explicitly not in scope:** anything requiring new endpoints (rotation), new
infrastructure (tickets, drain), or the M8 rate-limiting design.

---

## 1. Critical findings

### F-01 — `MAXLEN` safety cap never built — Redis OOM vector

- **Severity:** Critical · **Recommended:** M6c · **Effort:** S · **Docs to update:** spec §14 (new row, then resolved)
- **Description:** Spec §3 mandates a two-tier trim: approximate `MAXLEN ~` safety
  cap *plus* the scheduled `MINID` sweep. Only the sweep (hourly) was built —
  `MAXLEN` appears in the spec 3× and in code 0×. A bursty channel grows unbounded
  for up to an hour; with no rate limiting until M8, max-size bodies can exhaust
  host RAM (compose Redis has no `maxmemory` policy), at which point writes fail
  and ingest 500s for *every* channel and tenant. This deviation was never given a
  §14 row, unlike every other one.
- **Evidence:** `IngestService.appendToStream` — bare `XADD`, no trim options;
  `RetentionTrimmer` (hourly); `docker-compose.yml` (no Redis memory policy).
- **Suggested fix:**
  1. Add approximate `MAXLEN ~` to the `XADD` path — via Lettuce `XAddArgs`
     through `execute()`, or a Lua `XADD+MAXLEN` to keep it one round-trip.
  2. Make the cap configurable (`realtime.archive.stream-maxlen`, default ~50,000).
  3. Set `maxmemory` + `maxmemory-policy noeviction` on the compose Redis so dev
     matches the intended prod posture (see F-14).
- **Acceptance:** burst 200k events at one channel → `XLEN` stays ≈cap, Redis
  memory flat, other channels' ingest unaffected.

### F-02 — Replay API unbounded — one request can OOM the server

- **Severity:** Critical · **Recommended:** M6c · **Effort:** S · **Docs to update:** roadmap (note under replay/M6c)
- **Description:** `hotRange` issues XREAD with no `COUNT`; `coldRange` loads an
  unbounded JPA `List`. `GET /events?since=0` on a busy channel pulls the entire
  archive into heap and serializes it as one JSON blob — accidental self-DoS, no
  attacker required.
- **Evidence:** `ReplayService.hotRange` / `coldRange`; `ReplayController.replay`.
- **Suggested fix:**
  1. Add `limit` query param (default 500, server-side max ~5000, reject above max
     with 400).
  2. Apply `COUNT` to the hot XREAD and `LIMIT`/`Pageable` to the archive queries.
  3. Document the paging contract: clients narrow `since` to the last received ID
     (fits the existing cursor model — no offset scheme needed).
- **Acceptance:** `since=0` against a 1M-row archive returns ≤ max rows in
  < 1s with flat heap.

### F-03 — No API key rotation; `revoked_at` is dead schema

- **Severity:** Critical · **Recommended:** M8 · **Effort:** M · **Docs to update:** roadmap M8 ships-list; spec §10
- **Description:** `revoked_at` is never written by any code path (verified by
  grep) and no endpoint rotates, revokes, or lists keys. A leaked key can only be
  remediated by deleting the channel — new webhook URL, sender reconfiguration.
  For the milestone titled "Security Hardening" to omit rotation is a scope hole.
- **Evidence:** `ApiKey.revokedAt` (column only); `ChannelController` (no key
  endpoints); `WebhookAuthService` (checks active key only).
- **Suggested fix:**
  1. `POST /channels/{id}/keys/rotate` (JWT + ownership): create new key, set
     `revoked_at` on the old, return the raw new key once. Decide: immediate
     revoke (simple) vs. grace window (needs `grace_until` column, V5 migration) —
     recommend immediate for v1, grace as fast-follow.
  2. `GET /channels/{id}/keys`: suffixes + `created_at`/`revoked_at` (audit trail
     the schema already wants to be).
  3. Surface "Rotate" in the channel-detail page next to the masked key.
- **Acceptance:** rotate → old key gets 401, new key 202s; keys list shows both
  with correct timestamps.

### F-04 — Auth endpoints unthrottled (bcrypt CPU DoS + brute force)

- **Severity:** Critical · **Recommended:** M8, implemented *first* · **Effort:** (in M8) · **Docs to update:** none — already in M8; this raises its priority
- **Description:** Already roadmap'd ("5 attempts/min/IP"), but worth stating the
  threat plainly: every login attempt burns ~100ms of bcrypt CPU with no throttle,
  so a trivial script can saturate a small instance *and* brute-force weak
  passwords (see F-24, no password policy yet). This is the highest-value security
  item left, not one bullet among equals.
- **Suggested fix:** implement the M8 auth rate-limit slice before any other M8
  work; consider a slightly tighter login budget than the generic ingest bucket.
- **Acceptance:** scripted login burst → 429s after the budget, app stays
  responsive; legitimate login unaffected.

### F-05 — Shared Lettuce connection + `BLOCK` XREAD head-of-line risk

- **Severity:** Critical *if confirmed* · **Recommended:** verify pre-M7; fix in M8/M10 · **Effort:** S–M · **Docs to update:** spec §14 (new row either way, with verdict)
- **Description:** Every WS session runs `XREAD BLOCK 15s` through the default
  *shared* Lettuce connection (`shareNativeConnection=true`). On one TCP
  connection, a blocking command stalls every command behind it — idle WS clients
  may be delaying ingest `XADD`s by seconds. Unverified without a live test, but
  the mechanism is well-documented Lettuce behavior and the symptom (ingest
  latency coupled to idle WS count) is directly testable.
- **Evidence:** `ChannelWebSocketHandler.poll` (`BLOCK_TIMEOUT=15s`);
  `RedisConfig` (single shared template, no dedicated blocking factory).
- **Suggested fix:**
  1. **Verify first:** baseline webhook p50/p99 → open 3 idle WS sessions →
     re-measure. If latency shifts, confirmed.
  2. If confirmed: dedicated `LettuceConnectionFactory`
     (`shareNativeConnection=false`) / separate template for the WS handler's
     blocking reads; keep the shared template for ingest.
- **Acceptance:** idle WS count (0 → 10) produces no measurable shift in ingest
  latency; verdict recorded in §14 regardless of outcome.

---

## 2. High findings

### F-06 — WS resume has no Postgres fallback

- **Severity:** High · **Recommended:** M8 · **Effort:** M · **Docs to update:** spec §14 (new row); possibly §3
- **Description:** The §3 sequence diagram shows the WS gateway falling through to
  Postgres when `last_id` predates the stream; the handler never touches the
  archive. Resume with an old ID silently returns only the surviving hot window —
  a silent gap in the product's defining guarantee. The replay API has the handoff;
  the live path doesn't.
- **Evidence:** `ChannelWebSocketHandler.tail`/`poll` (Redis only) vs.
  `ReplayService.replay` (hot+cold merge).
- **Suggested fix:** on connect with `last_id`, resolve the earliest hot ID
  (reuse the `earliestHotId` pattern); if `last_id` predates it, emit the cold
  slice from Postgres first (extract a shared resume-position resolver with
  `ReplayService` rather than duplicating the merge), then join the live tail.
  If deliberately deferred instead, narrow the stated guarantee loudly — do not
  leave the diagram promising what the code doesn't do.
- **Acceptance:** trim hot stream, resume over WS with an old `last_id` → full
  ordered history including archived events, then live tail, no gap, no dupes.

### F-07 — `coldRange` exclusive lower bound drops same-ms events

- **Severity:** High · **Recommended:** M6c · **Effort:** XS · **Docs to update:** spec §14 item 3 (narrow or resolve)
- **Description:** The no-hot-data branch uses
  `findBy…ReceivedAtGreaterThan` (exclusive), but the in-memory
  `StreamIds.compare` filter downstream is already exact on both ends. Events in
  the same millisecond as `since` with a higher sequence number are excluded by
  SQL and unrecoverable — silent loss on the cold path. Half of tracked §14 item 3
  is a one-word fix.
- **Evidence:** `ReplayService.coldRange` (`until == null` branch) + the exact
  `.filter(e -> StreamIds.compare(e.id(), since) > 0)`.
- **Suggested fix:** `GreaterThan` → `GreaterThanEqual`; let the precise in-memory
  filter do the exclusion. Cover with same-ms regression tests (see F-20).
- **Acceptance:** same-ms boundary tests pass; §14 item 3 updated to reflect what
  (if anything) remains.

### F-08 — Malformed `since`/`last_id` → 500 instead of 400

- **Severity:** High · **Recommended:** M6c · **Effort:** XS–S · **Docs to update:** none
- **Description:** `StreamIds.parse` throws `NumberFormatException` on garbage
  input. `GET /events?since=abc` → unhandled 500 via the generic handler — the
  same "reject where written" class of bug already fixed for filter operators.
- **Evidence:** `StreamIds.parse`; `ReplayController.replay` (no validation).
- **Suggested fix:** validate `since` (`^\d+(-\d+)?$`, allow `0`) → 400 on bad
  input; for WS `last_id`, close with a policy-violation code on garbage (or fall
  back to tail — decide and document).
- **Acceptance:** garbage `since` → 400 with structured body; no 500s in logs.

### F-09 — Null filter `field`/`value` → NPE on the ingest path

- **Severity:** High · **Recommended:** Pre-M6c · **Status:** Done (`38a4987`) · **Effort:** XS (actual: M) · **Docs to update:** spec §5, §14 row 10; `realtime-build-log.md`; `peer-review-triage.md`
- **Description:** `validate()` checks only `op`. A stored rule with null `field`
  or `value` NPEs inside `extract()`/`matches()` on the *next third-party
  webhook* — a 500 to the sender for someone else's misconfiguration.
- **Evidence:** `FilterEngine.validate` (op-only) vs. `extract`/`matches`
  (dereference without null checks).
- **Suggested fix:** `validate()` rejects null/blank `field` and null `value`
  with 400; add PUT-with-null-field tests.
- **Acceptance:** null-field PUT → 400; ingest after any stored rule set never NPEs.
- **Resolution:** Fixed in `38a4987` before M6c. Expanded beyond the initial finding:
  covers null/blank `field`, null `op`, null `value`, null element in array, null rules
  list, `@RequestBody(required = false)` for literal `null` bodies, `MAX_RULES_PER_CHANNEL = 50`,
  and write-time `JsonPath.compile()`. See `realtime-build-log.md` for the full multi-round writeup.

### F-10 — Filter persistence orphaned — no milestone owns it

- **Severity:** High · **Recommended:** M8 · **Effort:** M · **Docs to update:** roadmap M8 ships-list; spec §14 item 4 (re-point from M6b)
- **Description:** §14 item 4 says "planned for M6b"; M6b shipped without it, and
  no later milestone picks it up (M7 is ephemeral connection filters). Beyond
  restart-loss: with 2+ instances, rules set via A are invisible to B — identical
  webhooks get different verdicts per instance. The §8 "stateless, horizontally
  scalable" claim is false until this lands.
- **Evidence:** `ChannelFilterStore` (in-memory, unchanged since M3); spec §7
  already defines the `filter_rules` table; M5 never migrated it.
- **Suggested fix:**
  1. Create `filter_rules` per the §7 schema (migration + repository).
  2. Keep `ChannelFilterStore` as a read cache, write-through on PUT; invalidate
     across instances via Redis pub/sub `filters-changed:{channel}` — the exact
     signaling use §2 already blesses pub/sub for.
  3. Do *not* query Postgres per ingest (hot-path dependency runs against the
     F-14/§14-item-9 direction).
- **Acceptance:** set rules → restart → rules survive; two instances agree on
  verdicts; ingest latency unchanged.

### F-11 — No WS heartbeat — proxies kill quiet connections in prod

- **Severity:** High · **Recommended:** M10 prerequisite (must land before first prod deploy) · **Effort:** S · **Docs to update:** roadmap M10 ships-list; spec §9
- **Description:** The handler sends bytes only when events arrive. Cloudflare
  (M10) kills idle proxied WS connections after ~100s of no data; NATs and LBs
  have their own idle timeouts. Every quiet channel will flap to "Disconnected"
  in production — and the load test won't catch it (busy connections stay alive;
  idle ones die). No heartbeat code exists anywhere (verified).
- **Evidence:** `ChannelWebSocketHandler` (event-driven sends only); M10
  (Cloudflare, no heartbeat mention).
- **Suggested fix:** server ping (protocol ping or app-level heartbeat frame the
  client ignores) every ~30s; doubles as half-open detection. Add an explicit
  k6 idle-connection test to M10 (connect, wait 5 min silent, assert still open).
- **Acceptance:** idle connection survives 10 min behind Cloudflare; half-open
  connections detected within ~1 min.

### F-12 — No client auto-reconnect; no server retry in the poll loop

- **Severity:** High · **Recommended:** client ≤M9 (frontend-only, cheap — do any time); server retry M10 prereq · **Effort:** M · **Docs to update:** roadmap (M9 or M10 entry)
- **Description:** Any Redis error terminates a session's `poll()` Flux
  permanently; any network blip, deploy, or idle timeout (F-11) leaves the
  dashboard dark until manual refresh. The hook doesn't even send `last_id`, so
  reconnect couldn't resume if it existed.
- **Evidence:** `ChannelWebSocketHandler.poll` (no error resume);
  `useChannelSocket` (no reconnect, no `last_id`, `onclose` is terminal).
- **Suggested fix:**
  1. Client: reconnect with exponential backoff + jitter (jitter matters — see
     F-29), resuming from the last received ID.
  2. Server: `onErrorResume` in `poll` with backoff for transient Redis errors;
     distinguish fatal (auth/closed session) from transient; the 15s `BLOCK`
     already provides cancel checkpoints.
- **Acceptance:** kill Redis for 10s → clients show "reconnecting" → resume with
  no gap and no dupes; deploys (with F-13) are invisible.

### F-13 — No drain on deploy

- **Severity:** High · **Recommended:** M10 prerequisite · **Effort:** M · **Docs to update:** roadmap M10 ships-list; spec §9 (already promises it — mark implemented)
- **Description:** §9 promises drain-on-deploy; nothing implements it. With M10's
  `git push → deploy` and no client reconnect (F-12), every merge hard-kills all
  WebSockets permanently. The deploy pipeline is a self-inflicted outage machine.
- **Evidence:** no drain code (verified); `timeout-per-shutdown-phase: 20s`
  bounds the kill but doesn't soften it.
- **Suggested fix:** on shutdown — stop accepting, send close frame (1012 /
  service-restart + reconnect hint), wait grace period; wire Fly `preStop`-style
  ordering in M10 deploy config. Requires F-12 client reconnect to be worth it —
  land them together.
- **Acceptance:** deploy mid-session → clients reconnect within seconds, no
  manual refresh, no gap (via F-06/F-12 resume).

### F-14 — Production Redis requirements undocumented

- **Severity:** High · **Recommended:** M10 · **Effort:** S (config + docs) · **Docs to update:** roadmap M10; new `docs/operations.md` or deploy notes
- **Description:** M10 says "managed Redis" with no requirements. Two are
  load-bearing for stated guarantees: (1) eviction policy **must** be
  `noeviction` — `allkeys-lru` silently evicts dedup keys (→ duplicates
  rebroadcast) and stream entries (→ invisible gaps); (2) persistence (RDB/AOF)
  is undecided — a restart currently wipes hot window + dedup keys, and the WS
  path can't reach the archive (F-06), so it's live-traffic amnesia.
- **Suggested fix:** M10 infra checklist — `noeviction`, stated RPO (RDB+AOF),
  memory sizing + alerts, password/TLS, version pin; mirror the policy on compose
  Redis (see F-01) so dev matches prod posture.
- **Acceptance:** documented checklist exists; staging Redis verified
  `noeviction`; restart drill shows bounded, understood loss.

### F-15 — M8's HMAC item is the wrong feature

- **Severity:** High (scope, not code) · **Recommended:** decision before M8 starts · **Effort:** XS (decision + roadmap edit) · **Docs to update:** roadmap M8 ships-list
- **Description:** "HMAC webhook signing for the dashboard's test-event feature"
  is cryptographically meaningless — signing exists so a *receiver* verifies a
  *sender*, and the test-event path is an internal method call with no HTTP hop
  and no third party. The real feature is the mirror image: *verifying inbound*
  signatures from senders (Stripe, GitHub, Svix-style) — per-channel secrets +
  ingest-path verification — which is far bigger than one bullet, since every
  provider's scheme differs.
- **Suggested fix:** cut the test-event HMAC line; choose explicitly —
  (a) inbound verification for 1–2 providers as its own designed pass, or
  (b) explicit post-MVP stance with API keys as the v1 auth story. Either is
  defensible; the current line is neither.
- **Acceptance:** M8 entry states the chosen option with reasoning; no HMAC code
  ships without a verifier.

### F-16 — M7's inbound control-frame protocol is undesigned

- **Severity:** High (design) · **Recommended:** M7 planning (before M7 code) · **Effort:** S (design) · **Docs to update:** roadmap M7 entry
- **Description:** M7 offers "query param or post-connect control frame," but the
  handler is send-only (`session.send`, never `session.receive()` — verified).
  The control-frame option needs a designed sub-protocol: frame envelope, actions
  (set/clear filter), validation, error signaling over the socket, unknown-action
  and malformed-frame behavior, interaction with resume. None of this is in the
  three-line M7 entry.
- **Suggested fix:** write the mini-spec first (same discipline as the M6b
  blueprint): envelope shape, action set, error frames, validation rules,
  `last_id`+filter interaction. Then implement.
- **Acceptance:** mini-spec exists and is reviewed before M7 code starts;
  malformed control frames get error frames, never silent drops or 500s.

### F-17 — M7 filter cost is multiplicative — needs parse-once refactor

- **Severity:** High · **Recommended:** M7 (part of the milestone, not M10 fallout) · **Effort:** S–M · **Docs to update:** roadmap M7 entry
- **Description:** `matches()` calls `JsonPath.read(rawBody, path)` per rule —
  re-parse + path recompile per rule per event. The build log deferred the
  controller-vs-engine double-parse as "marginal," but under M7 the cost becomes
  *events × connections × rules* parses/sec on shared CPU. Without a refactor,
  the M10 load test benchmarks JsonPath recompilation, not the architecture.
- **Evidence:** `FilterEngine.matches`/`extract`; build-log M6a double-parse note.
- **Suggested fix:** parse once per event (per XREAD batch), precompile paths
  (`JsonPath.compile()`), share the parsed document across connections/rules;
  measure before/after under the M10 k6 script.
- **Acceptance:** filter CPU/event flat as connections scale (within reason);
  load-test tuning notes record the measurement.

### F-18 — Slow-consumer buffer + `gap` frame unimplemented, untracked

- **Severity:** High · **Recommended:** M7 · **Effort:** M · **Docs to update:** spec §14 (new row, then resolved); roadmap M7
- **Description:** Spec §9 specifies a bounded per-connection buffer with
  drop-oldest + `{"type":"gap","dropped":N}` control frame. Nothing implements it
  and no §14 row tracks the gap: a slow client today grows server-side buffering
  unboundedly (`session.send` over an infinite Flux). It fits M7 naturally —
  per-connection state is that milestone's whole subject.
- **Evidence:** `ChannelWebSocketHandler.stream` (unbounded `session.send`);
  spec §9 vs. §14 (promised, never tracked).
- **Suggested fix:** bound the per-connection outbound buffer (~1,000 events or a
  byte cap); on overflow drop oldest + emit `gap` frame with resume cursor;
  client surfaces "resync" state (pairs with F-12 resume).
- **Acceptance:** artificially slow consumer → single `gap` frame, bounded server
  memory, client offers one-click resync.

### F-19 — Frontend event list grows unbounded

- **Severity:** Medium · **Recommended:** with F-12 · **Effort:** XS–S · **Docs to update:** none
- **Description:** `events` state grows forever; a hot channel left open becomes
  thousands of DOM nodes. The backend got a bounded `ArchiveWriter` buffer in
  M6a; the panel needs the equivalent. Also cap the paused queue — a long pause
  on a hot channel has the same effect on resume flush.
- **Evidence:** `useChannelSocket` (`setEvents(prev => [...prev, e])`, no cap);
  `EventPanel` (renders all).
- **Suggested fix:** keep the latest ~500 rendered events with a "showing latest
  N" note; cap the paused queue similarly (drop-oldest + counter).
- **Acceptance:** 50k-event soak — DOM node count and memory flat, UI responsive.

### F-20 — No `ReplayService`/`IngestService` regression tests

- **Severity:** High · **Recommended:** M6c · **Effort:** S–M · **Docs to update:** none
- **Description:** The highest-risk logic has zero automated coverage: no
  `ReplayServiceTest`, no `IngestServiceTest`, no trimmer/handler tests. The
  replay boundary bug was found by hand-tracing — exactly what a regression test
  is for — and `ReplayService` with mocked Redis/archive is very testable.
- **Evidence:** `src/test` file list (13 files, none covering replay/ingest/handler).
- **Suggested fix:** `ReplayServiceTest` boundary matrix (no-trim / partial-trim /
  full-trim / empty stream / same-ms — see F-07); `IngestServiceTest`
  filter→dedup→accept matrix with mocked Redis/store/writer.
- **Acceptance:** boundary matrix green; a reintroduction of the M4 overlap bug
  fails loudly.

---

## 3. Medium findings

### F-21 — WS `?token=` will land in production access logs

- **Severity:** Medium · **Recommended:** M8 · **Effort:** M (tickets) / XS (scrub minimum) · **Docs to update:** spec §10; `SECURITY.md`
- **Description:** The query-param workaround (correct for browsers) puts bearer
  tokens in URLs, which production access logs and Cloudflare logs record. Fine
  for dev; needs a stance before M10.
- **Suggested fix:** preferred — short-lived single-use WS tickets: `POST
  /channels/{id}/ws-ticket` (JWT) mints a ~60s ticket, WS redeems via `?ticket=`
  (Slack/Pusher pattern; leaked tickets are near-worthless). Minimum — scrub
  `token=` from access logs and explicitly accept Cloudflare-log exposure in
  `SECURITY.md`.
- **Acceptance:** (preferred) tickets redeem once and expire; (minimum) no token
  material in any log sample.

### F-22 — `ArchiveWriter` discards whole batch on single conflict

- **Severity:** Medium · **Recommended:** M8 · **Effort:** S–M · **Docs to update:** none (feeds F-27's metric)
- **Description:** One conflicting row discards up to 500 already-drained events
  (best-effort by design, spec §9 — but coarser than it needs to be), and any
  flush failure is log-only with no metric to alert on.
- **Evidence:** `ArchiveWriter.flush` catch blocks (log, no retry, no metric).
- **Suggested fix:** on batch conflict, fall back to per-row save ignoring
  conflicts (or pre-check existing stream IDs); emit an `archive_lag` /
  `archive_dropped_total` hook for M9 (see F-27). Keep best-effort semantics —
  just make the effort finer-grained and observable.
- **Acceptance:** single-dupe batch → 499/500 archived; failures visible as a
  metric, not just a log line.

### F-23 — Token revocation / password-change story undocumented

- **Severity:** Medium · **Recommended:** M8 (`SECURITY.md`) · **Effort:** XS (docs) · **Docs to update:** `SECURITY.md` (new in M8)
- **Description:** Stolen JWT valid 24h with no revocation; no password-change
  endpoint exists to pair rotation with. Acceptable for MVP — but it must be a
  *documented stance* (rotation = global secret change = everyone logged out),
  not a discovery. Backlog: `token_version` on user, bumped on password change.
- **Suggested fix:** state the stance in `SECURITY.md`; file token-versioning as
  post-MVP backlog.
- **Acceptance:** `SECURITY.md` answers "how do I revoke a token?" honestly.

### F-24 — Validation hardening batch

- **Severity:** Medium · **Recommended:** M8 · **Effort:** S · **Docs to update:** none
- **Description:** Small gaps, one theme (untrusted input → 500s or weak
  credentials): no password minimum; no `@Size` caps (300-char channel name =
  `DataIntegrityViolation` 500, not 400); email case-sensitivity permits
  `A@x`/`a@x` duplicates; `CurrentTenant`'s unchecked principal cast.
- **Suggested fix:** password `@Size(min=8, max=72)` (72 = bcrypt's truncation
  cap — document why); `@Size(max=255)` on channel/tenant names; lowercase
  emails at signup/login boundary; `instanceof` guard in `CurrentTenant`.
- **Acceptance:** oversized/weak inputs → 400s; case-variant login works;
  no 500s from any validation-shaped input.

---

## 4. Low / process / polish (compact)

| ID | Item | Fix | Milestone |
|----|------|-----|-----------|
| F-25 | Prometheus cardinality: never label by `channelId` (UUIDs = unbounded series) | Cardinality rule in M9 notes; aggregate or top-N only | M9 |
| F-26 | Health semantics: Postgres-down should read *degraded*, not *down*, after the M8 key-cache lands | Custom health indicator design in M9 | M9 |
| F-27 | M9 metric list misses the failure-mode metrics (archive lag, dedup-hit ratio, filter-drop rate, hot-vs-cold replay ratio, trim counts) | Extend the M9 metric list; archive-lag is the highest value (watches F-22/§14-item-9 failure modes) | M9 |
| F-28 | M10 schedules Postgres backups but no restore drill | Document + run one restore to staging | M10 |
| F-29 | Reconnects need jitter or every deploy causes a resume storm (N simultaneous XRANGEs; Postgres too once F-06 lands) | Jitter in F-12 client backoff; k6 resume-storm scenario in M10 | M10 (with F-12/F-13) |
| F-30 | Doc drift: roadmap M6b still says "Thymeleaf + HTMX"; spec schema lacks `key_suffix`; §14 item 4 still points at M6b | Fix the three lines + add §14 rows for F-01/F-05/F-06/F-18 | M6c (docs, cheap) |
| F-31 | `docker compose up --build` doesn't serve the dashboard (Dockerfile never got the blueprint's Node stage) | Already deferred to M10 — just don't let the README quickstart and reality diverge silently until then | M10 (planned) |
| F-32 | Entire project is one git commit; history doesn't reflect M0→M6b | Commit per milestone going forward (can't rewrite the past cheaply — start now) | Immediate / process |
| F-33 | Channel delete is one click, no confirm; dashboard never calls the replay API (M6b implied "show last N") | Confirm dialog (XS); replay-history view → backlog/M11 | Backlog / M11 |

---

## 5. Milestone assignment summary

| Milestone | Gains from this doc | Notes |
|-----------|---------------------|-------|
| **M6c** (proposed, pre-M7) | F-01, F-02, F-07, F-08, F-09, F-20, F-30; F-05 verification | Small blocking-correctness batch; M6a precedent. Alternatively fold into M7 with separate exit criteria. |
| **M7** Per-connection filtering | F-16 (design first), F-17, F-18 | M7 grows from 3 lines to a real milestone: sub-protocol spec + parse-once + slow-consumer handling. |
| **M8** Rate limiting & hardening | F-03, F-04 (first), F-06, F-10, F-15 (scope decision), F-21, F-22, F-23, F-24; F-05 fix if verified | M8 becomes the security-correctness milestone: rotation + key-cache (§14-9, already triaged) + filter persistence + validation. Heaviest milestone — consider splitting if it balloons. |
| **M9** Observability | F-12 (client half), F-25, F-26, F-27; F-19 with F-12 | M9 gains the failure-mode metrics that watch the failure modes this doc names. |
| **M10** Load-tested & deployed | F-11, F-13, F-14, F-28, F-29, F-31; F-12 must-complete; F-05 in load-test plan | Nothing deploys to prod before F-11/F-12/F-13 land — they are prerequisites, not stretch goals. |
| **Backlog / M11** | F-33; token-versioning (F-23 follow-up); inbound signature verification if F-15 chooses (a) | — |
| **Process** | F-32 | Immediate, free. |

## 6. Spec §14 rows to add (checklist for the triage pass)

- [ ] F-01 (`MAXLEN` missing) → resolve when fixed
- [ ] F-05 (shared-connection hypothesis) → record verdict either way
- [ ] F-06 (WS cold fallback missing) → open until M8
- [ ] F-18 (§9 slow-consumer handling missing) → open until M7
- [ ] F-10 → re-point item 4 from M6b to M8
- [ ] F-07 → narrow item 3 to whatever remains after the one-word fix

## 7. Working this doc

- **Triage:** for each item, accept (→ roadmap + §14 as noted), reject (record
  why, one line), or defer (record where). An un-triaged row is the failure mode.
- **Done means:** fix merged + acceptance criterion checked + build-log entry +
  §14/roadmap updated + this row marked `Done (<commit>)`.
- **Don't let this become a second §14:** §14 stays the spec's table of record;
  this doc is the review's input queue and should shrink to all-Done over M6c–M10.