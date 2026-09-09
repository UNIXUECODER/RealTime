# realtime

Webhook in. WebSocket out. Done.

A multi-tenant webhook-to-WebSocket relay: point any webhook sender at a channel, subscribe to that channel over a WebSocket, and receive events live — with gap-free reconnection, per-connection filtering, and a replay window. Built on Spring WebFlux, Reactor Netty, Redis Streams, and PostgreSQL.

See [`realtime-architecture-spec.md`](./realtime-architecture-spec.md) for the full design and [`realtime-roadmap.md`](./realtime-roadmap.md) for the milestone plan this is being built against.

## Status

**M0 — Bootstrap.** No features yet — this milestone proves the environment: a Spring Boot app that starts cleanly alongside Redis and Postgres containers, with a working health endpoint. Business logic starts in M1.

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

Requires JDK 21 and Maven 3.9+ installed.

```bash
mvn spring-boot:run
```

## Stack

- **Java 21** / **Spring Boot 4.1.x**
- **Spring WebFlux** + **Reactor Netty** — reactive ingest and WebSocket handling
- **Redis Streams** — hot event log, live fan-out, gap-free resume
- **PostgreSQL** — tenants, config, cold event archive
- **Micrometer + Prometheus** — metrics (wired up now, meaningful from M9 onward)

## License

MIT — see [`LICENSE`](./LICENSE).
