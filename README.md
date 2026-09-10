# realtime

Webhook in. WebSocket out. Done.

A multi-tenant webhook-to-WebSocket relay: point any webhook sender at a channel, subscribe to that channel over a WebSocket, and receive events live — with gap-free reconnection, per-connection filtering, and a replay window. Built on Spring WebFlux, Reactor Netty, Redis Streams, and PostgreSQL.

See [`realtime-architecture-spec.md`](./realtime-architecture-spec.md) for the full design and [`realtime-roadmap.md`](./realtime-roadmap.md) for the milestone plan this is being built against.

## Status

**M3 — Channel Filtering + Pipeline Hardening.** Channel-level filter rules (JSONPath, AND-combined) gate what reaches the stream; every response carries an `X-Request-Id` correlated to the server log line for that request; malformed JSON and oversized bodies get clean 400/413s instead of unhandled errors. Filter rules are stored in-memory for now (`ChannelFilterStore`) — real persisted, tenant-scoped channels land in M5.

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

## Stack

- **Java 21** / **Spring Boot 4.1.x**
- **Spring WebFlux** + **Reactor Netty** — reactive ingest and WebSocket handling
- **Redis Streams** — hot event log, live fan-out, gap-free resume
- **PostgreSQL** — tenants, config, cold event archive
- **Micrometer + Prometheus** — metrics (wired up now, meaningful from M9 onward)

## License

MIT — see [`LICENSE`](./LICENSE).
