# realtime

Webhook in. WebSocket out. Done.

A multi-tenant webhook-to-WebSocket relay: point any webhook sender at a channel, subscribe to that channel over a WebSocket, and receive events live — with gap-free reconnection, per-connection filtering, and a replay window. Built on Spring WebFlux, Reactor Netty, Redis Streams, and PostgreSQL.

See [`realtime-architecture-spec.md`](./realtime-architecture-spec.md) for the full design and [`realtime-roadmap.md`](./realtime-roadmap.md) for the milestone plan this is being built against.

## Status

**M5 — Multi-Tenancy & Auth.** Real tenants, users, channels, and API keys — `POST /auth/signup` and `/auth/login` issue JWTs; channel CRUD (`/channels`) is tenant-scoped, enforced at the query layer (spec §10); the webhook path now requires a real channel and a valid `X-Api-Key`. `/ws/{channelId}`, replay, and filter-config remain unauthenticated for now — a deliberate, tracked scope boundary (see `realtime-architecture-spec.md` §14 and `realtime-build-log.md`), not an oversight. No dashboard UI yet — everything here is exercised via API calls.

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

Requires a raw WebSocket client — [`websocat`](https://github.com/vi/websocat) is the easiest ("curl for WebSockets").

```bash
# Terminal 1 — connect fresh (no last_id = tail only, no history)
websocat ws://localhost:8080/ws/test
```

```bash
# Terminal 2 — send an event, watch it arrive in Terminal 1 immediately
curl -X POST localhost:8080/webhook/test -d '{"type":"live.test"}'
```

Now the resume check — the actual point of M2:

1. In Terminal 1, note the `"id"` from the last frame you received, then disconnect (Ctrl+C).
2. Send 3 more webhook events while disconnected.
3. Reconnect with that ID: `websocat "ws://localhost:8080/ws/test?last_id=<that-id>"`.
4. You should receive **exactly those 3 events, in order — no gap, no duplicates** — and then continue receiving anything sent after that live, with no visible transition between "catching up" and "live."

### Try channel filtering

```bash
# Only accept events where type == "payment.failed"
curl -X PUT localhost:8080/channels/test/filters \
  -H "Content-Type: application/json" \
  -d '[{"field":"type","op":"==","value":"payment.failed"}]'

# This one is filtered out — never appears in XRANGE
curl -X POST localhost:8080/webhook/test -d '{"type":"payment.succeeded"}'

# This one passes the filter — appears in XRANGE as usual
curl -X POST localhost:8080/webhook/test -d '{"type":"payment.failed"}'

# Remove the filter
curl -X DELETE localhost:8080/channels/test/filters
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

```bash
# Send a couple of events to a fresh channel
curl -X POST localhost:8080/webhook/m4-test -d '{"n":1}'
curl -X POST localhost:8080/webhook/m4-test -d '{"n":2}'

# Replay everything from the beginning — served entirely from the hot Redis Stream
curl "localhost:8080/channels/m4-test/events?since=0"
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

## Stack

- **Java 21** / **Spring Boot 4.1.x**
- **Spring WebFlux** + **Reactor Netty** — reactive ingest and WebSocket handling
- **Redis Streams** — hot event log, live fan-out, gap-free resume
- **PostgreSQL** — tenants, config, cold event archive
- **Micrometer + Prometheus** — metrics (wired up now, meaningful from M9 onward)

## License

MIT — see [`LICENSE`](./LICENSE).
