# realtime

Webhook in. WebSocket out. Done.

A multi-tenant webhook-to-WebSocket relay: point any webhook sender at a channel, subscribe to that channel over a WebSocket, and receive events live — with gap-free reconnection, per-connection filtering, and a replay window. Built on Spring WebFlux, Reactor Netty, Redis Streams, and PostgreSQL.

See [`realtime-architecture-spec.md`](./realtime-architecture-spec.md) for the full design and [`realtime-roadmap.md`](./realtime-roadmap.md) for the milestone plan this is being built against.

## Status

**M1 — Ingest-to-Stream Core.** Webhooks land in a per-channel Redis Stream, deduplicated by default (SHA-256 of channel + body — see `realtime-architecture-spec.md` §4). No auth yet, no live delivery yet — that's M2.

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

## Stack

- **Java 21** / **Spring Boot 4.1.x**
- **Spring WebFlux** + **Reactor Netty** — reactive ingest and WebSocket handling
- **Redis Streams** — hot event log, live fan-out, gap-free resume
- **PostgreSQL** — tenants, config, cold event archive
- **Micrometer + Prometheus** — metrics (wired up now, meaningful from M9 onward)

## License

MIT — see [`LICENSE`](./LICENSE).
