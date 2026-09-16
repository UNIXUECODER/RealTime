# Peer Review Triage — Resolution & Restructured Roadmap

Every item from `Peer-Review.md` gets an explicit decision below — accept (with destination), reject, or simplify — following the review's own principle that an un-triaged row is the failure mode. The resulting shape is **more milestones, not more total work**: the same 33 findings, organized so no single milestone is the undifferentiated pile that made this feel unmanageable. This mirrors exactly what M6a/M6b already proved works for this project.

**Guiding principle for every "simplify" decision below:** where the review offered a "simpler v1" option, take it, and file the fuller version as a fast-follow rather than blocking on it. Nothing here is a company with paying customers yet — correctness and honesty about limits matter more than building the maximally robust version of everything on the first pass.

---

## Rejected

**F-32** (single-commit history) — **factually wrong**. I checked the actual GitHub repo directly: 9 separate, properly-messaged milestone commits through M6a, and M6b landed the same way. Not invalidating the rest of the document over this, but worth knowing the reviewer didn't run `git log` (or looked at something stale) — code-level findings here have checked out excellently on every spot-check; this process-level one didn't.

---

## Done, before M6c

**F-09** (null filter `field`/`value` → NPE) is fixed, and grew well past the "two null checks" originally estimated here. Full write-time validation now covers null/blank `field`, null `op`, null `value`, a null element inside the rule array, and a null rules list itself — the last of these normalized consistently across `FilterEngine`, `ChannelFilterController`, and `ChannelFilterStore` rather than fixed in just one place, mirroring `matches()`'s existing "null/empty list = match everything" stance.

Two related bugs surfaced and got fixed in the same pass, neither one originally named by this review: `extract()` previously caught only `PathNotFoundException`, so a malformed-but-non-null JSONPath field (e.g. `"$[unclosed"`) crashed ingest uncaught on every subsequent webhook to that channel — same failure shape as F-09, just a different exception class; and `ChannelFilterController.setRules` 400'd a literal JSON `null` body with a misleading "No request body" instead of treating it as "no rules," a third occurrence of the exact `@RequestBody(required=true)`-treats-null-as-missing pattern already named twice in M6b's build-log entry.

Also went past the finding on purpose, not by accident: a `MAX_RULES_PER_CHANNEL` cap (50) and write-time JSONPath compile validation were added proactively as filter-specific hardening, not in response to a named finding — see spec §5 and §14 row 10 for the reasoning and the open question (cap value unvalidated against real load-test data).

Full story, all three rounds of test verification (unit, controller-slice, live Docker smoke test), in `realtime-build-log.md`.

Next: **M6c**.

---

## M6c — Pre-M7 Correctness Batch *(new milestone)*

**Ships:** F-01 (`MAXLEN ~` cap on the ingest `XADD`, configurable, default ~50k), F-02 (`limit` param + `COUNT`/`Pageable` on replay, default 500/max 5000), F-07 (`GreaterThan` → `GreaterThanEqual` in `coldRange`), F-08 (validate `since`/`last_id`, 400 not 500), F-20 (`ReplayServiceTest` boundary matrix, `IngestServiceTest` filter→dedup→accept matrix), F-30 (fix the three drifted doc lines: roadmap's stale "Thymeleaf+HTMX," spec's missing `key_suffix`, §14 item 4 still pointing at M6b).

**Also:** F-05 verification only (baseline webhook latency, open 3 idle WS sessions, re-measure) — a test to run, not a fix to build. Record the verdict in §14 either way; only becomes its own fix (in M8b) if confirmed.

**Effort reality check:** four of these seven items are XS (one-word fix, two null checks, a regex, three doc lines). The two Critical ones (F-01, F-02) are each independently S. This is genuinely the size of M6a, not bigger.

**Exit criteria:** burst 200k events at one channel → Redis memory flat; `since=0` on a 1M-row archive → capped page, no OOM; malformed `since`/null filter field → 400, not 500; same-ms boundary tests pass; F-05 verdict recorded.

**Depends on:** M6a, M6b.

---

## M7a — Per-Connection Filtering *(the M7 we already designed, plus one addition)*

**Ships:** everything already agreed in `m7-connection-filtering-blueprint.md` (query-param filter, `ConnectionFilterResolver`, `4400`/`4401` close codes, the WS integration test) **plus F-17**: parse the event JSON once per record (`JsonPath.parse(rawBody)` → `DocumentContext`), read each rule's path off that same parsed document, instead of `JsonPath.read(rawBody, path)` re-parsing from scratch per rule. Not optional to defer — per-connection filtering is exactly what turns this from "cost per event" into "cost per event × connections," on the code we're about to write. Shipping the feature and the fix in the same milestone means we never ship the regression at all.

**F-16** requires no action — already resolved by our own query-param decision; just needs reflecting in spec §5 once this ships (already planned).

**Exit criteria:** unchanged from the blueprint, plus: filter evaluation cost stays flat as connection count scales (a simple before/after timing comparison is enough here, not a full load-test rig — that's M10's job).

**Depends on:** M6c.

---

## M7b — Slow-Consumer Handling *(new milestone, split out of what would've bloated M7)*

**Ships:** F-18 — the bounded per-connection outbound buffer (~1,000 events or a byte cap) with drop-oldest + `{"type":"gap","dropped":N}` control frame, exactly as spec §9 already promises and nothing currently implements.

**Why its own milestone, not folded into M7a:** F-18 is `M` effort and a genuinely separate concern from filtering — it's about what happens when a *consumer* can't keep up, not what a connection asks to receive. Cramming both into one milestone is exactly the "unrealistic" pattern we're trying to avoid. Splitting costs nothing (F-17/F-16/original-M7 don't block on this) and keeps M7a shippable on its own.

**Exit criteria:** artificially slow consumer → single `gap` frame with a resume cursor, bounded server memory, no unbounded `session.send` backlog.

**Depends on:** M7a.

---

## M8a — Auth & Key Security *(first of three, split from the original M8)*

**Ships:** F-04 (auth rate-limiting — 5 attempts/min/IP, built *first* in this milestone, per the review's own emphasis that unthrottled bcrypt is the single highest-value item here), F-03 (key rotation — `POST /channels/{id}/keys/rotate`, `GET /channels/{id}/keys`; **simplified to immediate revoke, not a grace window** — a `grace_until` column and dual-key-valid window is real extra schema/logic for a v1 that doesn't need it yet), F-15 (HMAC scope decision — cut the meaningless test-event-signing line; explicitly state the chosen stance: inbound signature verification as its own future pass, or API keys as the documented v1 auth story), F-21 (**simplified to the minimum**: scrub `token=` from access logs, state the Cloudflare-log exposure plainly in `SECURITY.md` — the ticket-based upgrade is real new infrastructure, backlogged rather than blocking), F-23 (`SECURITY.md`: token revocation and password-change stance, honestly stated), F-24 (validation hardening: password `@Size(min=8,max=72)`, `@Size(max=255)` on names, lowercase emails at the auth boundary, fix `CurrentTenant`'s unchecked cast).

**Exit criteria:** scripted login burst → 429s, app stays responsive; rotate → old key 401s, new key 202s; oversized/weak inputs → 400s, never 500s; `SECURITY.md` answers "how do I revoke a token?" and "what's exposed in logs?" honestly.

**Depends on:** M7b.

---

## M8b — Rate Limiting & Ingest Resilience *(second of three — the milestone's original promise, kept focused)*

**Ships:** the original M8 scope unchanged — Bucket4j + Redis token bucket per channel; security headers; OWASP dependency check in CI — **plus** spec §14 item 9 (the Postgres-down ingest-auth cache, already decided months ago to live here): a Redis-backed cache of active channel/API-key pairs so ingest auth doesn't need a synchronous Postgres round-trip. If F-05 came back confirmed in M6c, its fix (dedicated non-shared Lettuce connection for WS blocking reads) lands here too.

**Exit criteria:** unchanged from the original M8 entry, plus §14 item 9 formally resolved (not just deferred) — a burst of webhook events with Postgres stopped now returns 202s, not 500s.

**Depends on:** M8a.

---

## M8c — Multi-Instance Correctness *(third of three, new)*

**Ships:** F-10 (filter persistence — the `filter_rules` table spec §7 already defines but M5 never migrated; `ChannelFilterStore` becomes a write-through read cache, invalidated across instances via Redis pub/sub `filters-changed:{channel}`; **not** a per-ingest Postgres query, which would just reintroduce item 9's problem), F-06 (WS resume falls through to Postgres when `last_id` predates the hot stream — reuse `ReplayService`'s hot/cold merge logic rather than duplicating it), F-22 (`ArchiveWriter` falls back to per-row save on batch conflict instead of discarding up to 500 events; emits a metric hook for M9 to pick up).

**Why separate from M8b:** these three share a theme — "this currently only works correctly on one instance, or silently loses data at a boundary" — distinct from rate limiting itself. The §8 "stateless, horizontally scalable" claim is false until F-10 lands specifically; that's worth its own clearly-labeled milestone rather than a line item buried in a bigger one.

**Exit criteria:** set filter rules → restart → survive; two instances agree on filter verdicts; trim hot stream, resume with an old `last_id` → full ordered history, no gap; single-dupe archive batch → 499/500 saved, not 0/500.

**Depends on:** M8b.

---

## M9 — Observability *(extended, no split needed — additions are small)*

**Ships:** unchanged original scope, **plus** F-25 (never label Prometheus metrics by `channelId` — a design rule applied while building the metrics, not separate work), F-26 (Postgres-down reads as *degraded*, not *down*, now that M8b's key-cache means ingest actually survives it), F-27 (extend the metric list: archive lag, dedup-hit ratio, filter-drop rate, hot/cold replay ratio, trim counts — archive lag is the highest-value addition, since it's what would have caught §14 item 9's failure mode in production).

**Depends on:** M8c.

---

## Pre-M10 frontend polish pass *(already agreed to exist — gains two functional items, not just visual work)*

**Adds to the already-planned pass:** F-12's client half (reconnect with exponential backoff + jitter, resuming from last received ID — currently `useChannelSocket` doesn't even send `last_id`, so reconnection couldn't resume if it existed) and F-19 (cap the rendered event list and the paused queue at ~500, "showing latest N" note — the exact same unbounded-growth pattern `ArchiveWriter` already got fixed for on the backend at M6a).

**Why here, not a new milestone:** both are frontend-only, both are cheap, and fragmenting frontend work across even more named milestones works against the whole reason we deferred polish to one pass in the first place.

---

## M10 — Load-Tested & Deployed *(extended, grouped internally rather than split)*

**Ships:** unchanged original scope, **plus**, grouped by what they're for:
- *Won't survive a real deploy without these* (true prerequisites, not stretch goals): F-11 (WS heartbeat — required before Cloudflare's ~100s idle-connection kill makes every quiet channel flap), F-12's server half (retry transient Redis errors in the poll loop, distinguish fatal from transient), F-13 (drain on shutdown — send a close frame + grace period before `git push` hard-kills every session).
- *Production config, not code*: F-14 (Redis `noeviction` + persistence policy, documented and mirrored on compose Redis).
- *Verification, not new infrastructure*: F-28 (run one actual restore drill against staging — proves the backups from this same milestone work), F-29 (jitter in the F-12 backoff + a k6 resume-storm scenario, so a deploy doesn't cause every client to hammer Postgres/Redis simultaneously).
- F-31 was already deferred here (dashboard build integration into the Docker image) — just don't let the README's quickstart and reality diverge silently until it lands.

**Exit criteria:** unchanged, plus: idle connection survives 10 minutes behind Cloudflare; killing Redis for 10s → clients reconnect with no gap, no dupes; a deploy mid-session is invisible to connected clients.

**Depends on:** M9.

---

## Backlog / M11

F-33 (channel-delete confirmation dialog; dashboard replay-history view), the token-versioning fast-follow from F-23, and inbound webhook signature verification if F-15's decision lands on option (a) rather than (b).

---

## Summary — what actually changes right now

The immediate next step doesn't change: **fix F-09** (two null checks, minutes of work), then start **M6c**. M7 becomes **M7a**, with F-17 folded in as non-optional. Everything else above is sequenced but not urgent — you don't need to hold all of it in your head at once, that's what this document is for.
