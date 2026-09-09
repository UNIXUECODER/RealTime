# Realtime — Milestone Roadmap to MVP

**Companion to:** `realtime-architecture-spec.md` (v2.1)

This roadmap is scope-based, not calendar-based — no weeks, no hours. Each milestone is a **vertical slice**: a specific, demoable capability you can point at and say "this works," built on top of everything before it. You don't move to the next milestone until the current one's exit criteria are true, full stop. That's the actual discipline mechanism here — not a deadline, a definition of "done enough to build on."

Each entry has four parts:
- **Ships** — what actually gets built.
- **Explicitly not in scope** — named on purpose, so scope doesn't quietly creep in mid-milestone.
- **Exit criteria** — the concrete, checkable thing that proves it's done. If you can't check it, it isn't done.
- **Depends on** — what has to be true already.

---

## M0 — Bootstrap

**Ships:** Repo scaffold, Spring Boot 3.3 project skeleton, `docker-compose.yml` with app + Redis + Postgres containers (empty schemas for now), `.gitignore`/`.editorconfig`/`LICENSE`, health endpoint via Actuator.

**Explicitly not in scope:** Any business logic. No webhook endpoint, no WS handler, no auth. This milestone proves the environment, nothing else.

**Exit criteria:** `git clone && docker-compose up` on a clean machine brings up the app, and `curl localhost:8080/actuator/health` returns 200.

**Depends on:** Nothing.

---

## M1 — Ingest-to-Stream Core

**Ships:** `POST /webhook/{channelId}` endpoint that validates the body (non-empty, size cap), computes the idempotency fingerprint (body-hash default per spec §4), checks it against Redis via `SETNX`, and `XADD`s the event to `stream:channel:{id}` if it's new.

**Explicitly not in scope:** Auth (any channel ID is accepted, no API key check yet), WebSocket delivery, filtering, persistence to Postgres. This milestone is purely "can an event get into the stream, exactly once."

**Exit criteria:** `curl` a webhook, confirm the entry via `redis-cli XRANGE stream:channel:test - +`. Send the identical payload twice — confirm only one entry exists in the stream.

**Depends on:** M0.

---

## M2 — Live Fan-out + Gap-free Resume

**Ships:** WS Gateway (`/ws/{channelId}`) that does `XREAD BLOCK 0` against the channel's stream and pushes frames to connected sessions. Resume support: a client can connect with `?last_id=<id>`, gateway does `XRANGE (last_id, +]` to catch up before switching to live tail (spec §3).

**Explicitly not in scope:** Auth, filtering, Postgres archive fallback for resumes older than the hot window (that's M4). Assume every resume request is within the still-live Redis stream.

**Exit criteria:** Open two WS clients on the same channel; `curl` a webhook; both receive the frame. Disconnect one client mid-stream, send 3 more events, reconnect with the last received ID — confirm it receives exactly those 3 events, no duplicates, no gaps.

**Depends on:** M1.

---

## M3 — Channel Filtering + Pipeline Hardening

**Ships:** Channel-level filter rules (JsonPath, AND-combined) gating what reaches `XADD`; proper error responses (400 on bad JSON, 413 on oversized body, 500 with a trace ID on failure); request tracing (`X-Request-Id`); graceful shutdown of WS sessions and Redis connections; Dockerfile finalized as a multi-stage build.

**Explicitly not in scope:** A UI for managing filter rules — configure them directly via seed data or a raw insert for now. The dashboard comes in M6.

**Exit criteria:** An event that fails a channel's filter rule never appears in the stream. A malformed JSON body returns 400 with a request ID in the response header, and that same ID appears in the server log line for the error.

**Depends on:** M1, M2.

---

## M4 — Cold Archive + Retention

**Ships:** Async batched writer that persists every ingested event to Postgres `events_archive` (with `redis_stream_id` recorded); scheduled Retention Trimmer job running `XTRIM MINID` for the 24h hot window (spec §3); replay API (`GET /channels/{id}/events?since=`) that transparently reads from Redis when the range is hot and falls back to Postgres when it isn't.

**Explicitly not in scope:** Per-plan-tier retention enforcement (7/30/90 days) — that's a config value read at query time once tenancy exists in M5; for now, archive retention can be unbounded or a single fixed default.

**Exit criteria:** Force-trim the hot stream in a test environment (manually run the trim job), then call the replay API for a time range that predates the trim — confirm it returns the same data, transparently served from Postgres.

**Depends on:** M1, M3.

---

## M5 — Multi-Tenancy & Auth

**Ships:** `users`, `tenants`, `channels`, `api_keys` tables and Flyway migrations; JWT-based auth for `/auth/signup` and `/auth/login`; per-channel API keys (hashed, shown once) validated on the webhook path; tenant isolation enforced at the repository layer (every query scoped by the authenticated principal's tenant, never a client-supplied field).

**Explicitly not in scope:** Any dashboard UI — this is the auth/data layer only, exercised via API calls or a test harness.

**Exit criteria:** Create two tenants via signup. Confirm tenant A's JWT cannot read, list, or delete tenant B's channels — not just that the UI hides them, that the API rejects it.

**Depends on:** M1–M4 (auth wraps the existing pipeline; nothing before this changes structurally).

---

## M6 — Dashboard MVP

**Ships:** Thymeleaf + HTMX pages: login, signup, channel list, channel detail (webhook URL, WS URL, masked API key, "send test event" button, live event panel).

**Explicitly not in scope:** Filter rule management UI, replay UI beyond a basic "show last N" button, polish/empty-states beyond functional. This is "usable," not "pretty."

**Exit criteria:** A person with no prior explanation can sign up, create a channel, click "send test event," and watch it appear in the live panel — with you standing there saying nothing.

**Depends on:** M5.

---

## M7 — Per-Connection Filtering

**Ships:** WS Gateway accepts a filter (query param or post-connect control frame) using the same AND-only DSL as channel filters (spec §5), evaluated per-connection before emitting each frame.

**Explicitly not in scope:** Persisting connection filters, OR/nested rule logic (named limitation, spec §5/§13).

**Exit criteria:** Two WS clients (or two dashboard tabs) connect to the same channel with different filters; send a mixed batch of events; confirm each client receives only the events matching its own filter.

**Depends on:** M2, M3.

---

## M8 — Rate Limiting & Security Hardening

**Ships:** Bucket4j + Redis token bucket per channel on ingest; rate limiting on auth endpoints (5 attempts/min/IP); HMAC webhook signing for the dashboard's test-event feature; security headers (`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, HSTS when behind HTTPS); OWASP dependency check wired into the build; `SECURITY.md`.

**Explicitly not in scope:** IP allowlisting (optional, defer unless a real need appears), account lockout email-reset flow (manual unlock is fine for v1).

**Exit criteria:** Scripted burst above a channel's configured rate limit returns 429s once the limit is hit, not 503s or crashes. `mvn org.owasp:dependency-check-maven:check` runs clean (or with only accepted/documented exceptions) in CI.

**Depends on:** M5.

---

## M9 — Observability

**Ships:** Prometheus metrics (`webhook_events_total`, `websocket_connections`, `event_broadcast_latency_seconds`, `event_persist_latency_seconds`); Grafana dashboard tracking the spec's stated SLO targets (§11); structured JSON logs with request/tenant/channel/event correlation IDs; `/actuator/health` checks for Redis and Postgres.

**Explicitly not in scope:** Alerting/paging setup — you're the only on-call for now; dashboards are for your own visibility, not incident response yet.

**Exit criteria:** Run a sustained load test (from M10's k6 script, borrowed early) while watching the Grafana p95 latency panel live — the number on screen should move in response to load, not sit static or error out.

**Depends on:** M2, M3, M4.

---

## M10 — Load-Tested & Deployed

**Ships:** k6 load test script and a documented tuning pass (`docs/performance.md`); deployment to Fly.io (or equivalent) with managed Postgres and Redis; HTTPS via Cloudflare in front; GitHub Actions CI (test + build on PR, deploy on merge to `main`); scheduled Postgres backups.

**Explicitly not in scope:** Multi-region deployment (explicit non-goal, spec §1), Kubernetes, blue/green deploys — a single-region, single-instance-class deploy with a clean rollback path is the bar.

**Exit criteria:** The production URL, over HTTPS, supports the full M1–M8 flow end-to-end from a machine that isn't yours. A `git push` to `main` results in a new deploy without manual steps.

**Depends on:** M1–M9 (this is "take everything built so far and make it reachable").

---

## M11 — MVP Launch Readiness

**Ships:** Unauthenticated `/playground` page (temporary channel, live curl-and-see demo — the strongest marketing asset you have, per the original plan's own instinct); full `README.md` (architecture diagram, quickstart, API reference); `docs/architecture.md`, `docs/filters.md`, `docs/webhook-signing.md`; a short walkthrough recording.

**Explicitly not in scope:** Pricing page, billing integration, Product Hunt/Show HN launch, content calendar — those are go-to-market activities that follow MVP, not part of it. This milestone is about the product being *legible to a stranger*, not about acquiring users yet.

**Exit criteria:** Send the playground link to someone who's never heard of the project. They understand what it does and see a live event appear, unaided, in under two minutes.

**This is MVP shipped.**

**Depends on:** M1–M10.

---

## After MVP

Everything past M11 — self-hosting polish, third-party integrations (Stripe/GitHub/Shopify presets), SSO, multi-region — is real work, but it's *post-MVP* work, and belongs in a separate roadmap once M11 is behind you and you have actual usage (or actual freelance conversations) telling you which of those matters first. Resist pulling any of it forward; the original plan's own anti-pattern list says it best: *ship at 80%, iterate in production.*
