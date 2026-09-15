# realtime

Webhook in. WebSocket out. Done.

A multi-tenant webhook-to-WebSocket relay: point any webhook sender at a channel, subscribe to that channel over a WebSocket, and receive events live — with gap-free reconnection, per-connection filtering, and a replay window. Built on Spring WebFlux, Reactor Netty, Redis Streams, and PostgreSQL.

See [`realtime-architecture-spec.md`](./realtime-architecture-spec.md) for the full design and [`realtime-roadmap.md`](./realtime-roadmap.md) for the milestone plan this is being built against.

## Status

**M6a — Close the Auth Gap + Pre-UI Hardening.** `/ws/{channelId}`, replay, and filter-config now require the same tenant ownership every other `/channels/**` endpoint holds — WS auth arrives via a `?token=` query param (browsers can't set custom headers on a WebSocket handshake). Also folded in: transactional signup/channel-creation (no more orphaned rows on partial failure), a fixed cascading delete, a hardened signup race, a bounded archive buffer, input validation, and a closed login-timing side channel — see `realtime-build-log.md` (M6a) for the full list and reasoning. No UI yet — that's M6b.

## Quickstart

```bash
docker-compose up --build
```

Then confirm it's alive:

```bash
curl localhost:8080/actuator/health
# {"status":"UP"}
```

### Running locally without Docker

Requires JDK 21 and Maven 3.9+ installed, plus a Redis instance reachable at `localhost:6379` (`docker-compose up redis` is the easiest way to get one).

```bash
mvn spring-boot:run
```

### Try the ingest path

```bash
curl -i -X POST localhost:8080/webhook/test \
  -H "Content-Type: application/json" \
  -d '{"type":"payment.succeeded","amount":4200}'
# HTTP/1.1 202 Accepted

redis-cli XRANGE stream:channel:test - +
# 1) 1) "1725900000000-0"
#    2) 1) "payload"
#       2) "{\"type\":\"payment.succeeded\",\"amount\":4200}"
#       3) "received_at"
#       4) "2026-09-10T12:00:00.000Z"
```

Send the exact same payload again — it still returns `202`, but nothing new appears in `XRANGE`. That's the idempotency check working: the fingerprint was already seen, so the duplicate is acknowledged (so the sender stops retrying) without being re-added to the stream.

### Try live delivery + gap-free resume

Requires a raw WebSocket client — [`websocat`](https://github.com/vi/websocat) is the easiest ("curl for WebSockets"). As of M6a, this needs a real channel and a JWT — see "Try signup, channel creation, and tenant isolation" below to get both first.

```bash
# Terminal 1 — connect fresh (no last_id = tail only, no history)
websocat "ws://localhost:8080/ws/<channel-uuid>?token=$TOKEN_A"
```

```bash
# Terminal 2 — send an event (needs the channel's API key), watch it arrive in Terminal 1
curl -X POST localhost:8080/webhook/<channel-uuid> -H "X-Api-Key: rtk_..." -d '{"type":"live.test"}'
```

Now the resume check — the actual point of M2:

1. In Terminal 1, note the `"id"` from the last frame you received, then disconnect (Ctrl+C).
2. Send 3 more webhook events while disconnected.
3. Reconnect with that ID: `websocat "ws://localhost:8080/ws/<channel-uuid>?token=$TOKEN_A&last_id=<that-id>"`.
4. You should receive **exactly those 3 events, in order — no gap, no duplicates** — and then continue receiving anything sent after that live, with no visible transition between "catching up" and "live."

Try connecting with `$TOKEN_B` (or no token at all) instead — the connection should close immediately with code `4401`, the actual M6a exit criteria for this endpoint.

### Try channel filtering

As of M6a, this requires a real channel and its owning tenant's JWT (see "Try signup, channel creation, and tenant isolation" below for `$TOKEN_A` and a channel UUID) — any raw string channelId (like the `test` used in earlier examples) no longer works here.

```bash
# Only accept events where type == "payment.failed"
curl -X PUT localhost:8080/channels/<channel-uuid>/filters \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '[{"field":"type","op":"==","value":"payment.failed"}]'

# This one is filtered out — never appears in XRANGE
curl -X POST localhost:8080/webhook/<channel-uuid> -H "X-Api-Key: rtk_..." -d '{"type":"payment.succeeded"}'

# This one passes the filter — appears in XRANGE as usual
curl -X POST localhost:8080/webhook/<channel-uuid> -H "X-Api-Key: rtk_..." -d '{"type":"payment.failed"}'

# Remove the filter
curl -X DELETE localhost:8080/channels/<channel-uuid>/filters -H "Authorization: Bearer $TOKEN_A"
```

### Try error tracing

```bash
curl -i -X POST localhost:8080/webhook/test -d 'not valid json'
# HTTP/1.1 400 Bad Request
# X-Request-Id: <some-id>
# {"requestId":"<some-id>","status":400,"error":"Bad Request","message":"Webhook body must be valid JSON",...}
```

The same `<some-id>` appears in the application log line for that request (`docker compose logs app`) — that's the point: a client-reported failure and a server log entry can always be tied together.

### Try the replay API (and the hot → cold handoff)

As of M6a, this also requires the owning tenant's JWT — see "Try signup, channel creation, and tenant isolation" below for `$TOKEN_A`.

```bash
# Send a couple of events to a fresh channel
curl -X POST localhost:8080/webhook/<channel-uuid> -H "X-Api-Key: rtk_..." -d '{"n":1}'
curl -X POST localhost:8080/webhook/<channel-uuid> -H "X-Api-Key: rtk_..." -d '{"n":2}'

# Replay everything from the beginning — served entirely from the hot Redis Stream
curl "localhost:8080/channels/<channel-uuid>/events?since=0" -H "Authorization: Bearer $TOKEN_A"
```

To actually exercise the hot → cold handoff (M4's real exit criteria), force a trim in a test environment — e.g. temporarily set `realtime.archive.hot-window` to something tiny like `1s`, restart, wait a couple of seconds so the scheduled sweep trims everything, then call the same replay URL again. Wait a couple more seconds for `ArchiveWriter`'s flush cycle to have run first, or the events won't be in Postgres yet when they get trimmed from Redis. You should get back **the same events**, now served transparently from `events_archive` instead — nothing in the response shape gives away which tier actually served it.

### Try signup, channel creation, and tenant isolation

```bash
# Sign up two separate tenants
TOKEN_A=$(curl -s -X POST localhost:8080/auth/signup \
  -d '{"tenantName":"Tenant A","email":"a@example.com","password":"password123"}' | jq -r .token)

TOKEN_B=$(curl -s -X POST localhost:8080/auth/signup \
  -d '{"tenantName":"Tenant B","email":"b@example.com","password":"password123"}' | jq -r .token)

# Tenant A creates a channel — note the returned apiKey, shown exactly once
curl -X POST localhost:8080/channels \
  -H "Authorization: Bearer $TOKEN_A" \
  -d '{"name":"orders"}'
# {"channelId":"<uuid>","name":"orders","apiKey":"rtk_..."}

# Tenant B tries to read Tenant A's channel — the actual M5 exit criteria
curl -i localhost:8080/channels/<tenant-a-channel-uuid> \
  -H "Authorization: Bearer $TOKEN_B"
# HTTP/1.1 404 Not Found — not 403. Indistinguishable from "this channel doesn't exist,"
# by design: the API never confirms another tenant's channel IDs are even real.
```

### Try authenticated webhook ingest

```bash
# Missing key
curl -i -X POST localhost:8080/webhook/<channel-uuid> -d '{"type":"test"}'
# HTTP/1.1 401 Unauthorized

# Real key from channel creation above
curl -i -X POST localhost:8080/webhook/<channel-uuid> \
  -H "X-Api-Key: rtk_..." \
  -d '{"type":"test"}'
# HTTP/1.1 202 Accepted
```

## Dashboard (M6b)

A TypeScript SPA (Vite + React + Tailwind) lives in `frontend/`, separate from the
Java project but built into it — its `npm run build` output lands directly in
`src/main/resources/static`, which Spring Boot's WebFlux auto-configuration serves
as-is. Same origin as the API, so there's no CORS configuration anywhere.

```bash
cd frontend
npm install
npm run dev     # dev server on :5173, proxies /auth, /channels, /ws to :8080
npm run test    # vitest
npm run build   # writes into ../src/main/resources/static
```

`npm run build` isn't wired into the Maven build yet — that's deliberately deferred
to M10 alongside the rest of CI/CD (see `realtime-roadmap.md`). Until then, run it
manually before starting the Spring Boot app if you want the dashboard served at `/`.

## Stack

- **Java 21** / **Spring Boot 4.1.x**
- **Spring WebFlux** + **Reactor Netty** — reactive ingest and WebSocket handling
- **Redis Streams** — hot event log, live fan-out, gap-free resume
- **PostgreSQL** — tenants, config, cold event archive
- **Micrometer + Prometheus** — metrics (wired up now, meaningful from M9 onward)
- **Vite + React + TypeScript + Tailwind** — dashboard (M6b), built into the Java app's static resources

## License

MIT — see [`LICENSE`](./LICENSE).
