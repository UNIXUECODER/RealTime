# Realtime — Build Log

Chronological record of what actually happened during implementation — framework surprises, real bugs caught by testing, and the reasoning behind each fix. Kept deliberately separate from `realtime-architecture-spec.md` (intended design) and `realtime-roadmap.md` (planned scope): this is the "what we actually hit and how we resolved it" record. Updated every milestone.

---

## M0 — Bootstrap

- **Stack lock-in updated:** the original plan pinned Spring Boot 3.3.x. By the time building started, 3.3, 3.4, and even 3.5 had all exited open-source support. Moved to **Spring Boot 4.1.1** — current supported line, Java 21 baseline unchanged from the original plan.

## M1 — Ingest-to-Stream Core

- **Redis bean collision (not actually a Boot 4 issue):** a custom `ReactiveRedisTemplate<String,String>` bean named `reactiveRedisTemplate` collided with Spring Data Redis's own auto-configured `reactiveStringRedisTemplate` bean — both assignable to the same generic type, causing `NoUniqueBeanDefinitionException` the moment a real constructor injection (`IngestService`) exercised it. This conditional-bean-by-name behavior has existed in `RedisReactiveAutoConfiguration` since Spring Boot 2.0; it simply had no injection point to break until M1. **Fix:** name the bean `reactiveStringRedisTemplate` directly, satisfying Boot's `@ConditionalOnMissingBean(name=...)` check so the duplicate is never created.
- **Boot 4 test-slice starter split:** `@WebFluxTest` requires `spring-boot-starter-webflux-test` as a separate dependency now — test-slice starters were split out of the monolithic `spring-boot-starter-test`.
- **Package move:** `@WebFluxTest` relocated from `org.springframework.boot.test.autoconfigure.web.reactive` to `org.springframework.boot.webflux.test.autoconfigure`.

## M2 — Live Fan-out + Gap-free Resume

- **Jackson 3 (Boot 4's new default JSON library):** package root moved `com.fasterxml.jackson` → `tools.jackson`; the checked `JsonProcessingException` was replaced by an unchecked `JacksonException`. `ObjectMapper` is now an *abstract base class* — the concrete JSON implementation is `JsonMapper` (`JsonMapper extends ObjectMapper`), mirroring the existing `XmlMapper`/`CsvMapper` pattern. Injecting the supertype `ObjectMapper` still resolves correctly against Boot's auto-configured `JsonMapper` bean via ordinary polymorphism.
- **Ordinary Java inference gap:** `redis.opsForStream()` sometimes needed an explicit `<String, String>` type witness when chained into `Flux.defer(...)` — unrelated to any framework version, just a generic-method-in-a-lambda inference limit.
- **Deliberate scope deviation, tracked in spec §14:** shipped one independent `XREAD` loop per WebSocket session rather than the spec's shared-reader-per-channel design. Correct by construction, less efficient at scale.

## M3 — Channel Filtering + Pipeline Hardening

- **RFC 9110 terminology update:** `HttpStatus.PAYLOAD_TOO_LARGE` deprecated in Framework 7.0 in favor of `CONTENT_TOO_LARGE`.
- **Wrong assumed exception type:** the actual exception WebFlux throws when `spring.codec.max-in-memory-size` is exceeded is `ContentTooLargeException` (replacing the now-also-deprecated `PayloadTooLargeException`), not `DataBufferLimitException` as originally assumed. Both extend `ResponseStatusException`, so the original bug wasn't "413 unhandled" — it was "413 handled by the general `ResponseStatusException` handler, with a null `getReason()` silently producing a null response message."

## M4 — Cold Archive + Retention

- **Spring Data Redis 4.1 gained native `XTRIM ... MINID` support** via `RedisStreamCommands.XTrimOptions`/`TrimOptions` — merged into the library only around October 2025. Verified against a real Redis instance before shipping, given how recent the API is. Both `XTrimOptions.of(...)` and `XTrimOptions.trim(...)` are real, equivalent static factories (`.of()` is a 4.1-added alias for `.trim()`).
- **Boot 4 modularization, again:** `FlywayAutoConfiguration` moved out of the monolithic `spring-boot-autoconfigure` into its own module. `flyway-core` alone is no longer sufficient — `spring-boot-starter-flyway` is required (confirmed via Spring's own "Modularizing Spring Boot" blog post, which describes this exact case).
- **Real logic bug (not a framework surprise):** `ReplayService`'s original hot/cold merge could return the same boundary event from both tiers — an inclusive Postgres `BETWEEN` up to `earliestHotId`'s own timestamp, combined with `hotRange` independently reading from `since`, both legitimately included the same event. Reproduced by hand-tracing the exact comparison logic (not just re-running the bug report), fixed by short-circuiting to hot-only when no gap exists, and strictly excluding the boundary ID from the cold slice when a gap does exist.
- **Known residual limitation, tracked in spec §14:** the cold-archive query keys off `receivedAt: Instant` (millisecond precision), which can't fully express a Redis stream ID's sequence-number-level exclusivity. Two events for the same channel landing in the exact same millisecond aren't perfectly disambiguated by a timestamp column alone.
- **Docker Compose startup race:** the app could start before Postgres/Redis were actually ready to accept connections. Fixed with `healthcheck` (`redis-cli ping`, `pg_isready`) plus `depends_on: condition: service_healthy`.

## M5 — Multi-Tenancy & Auth

- **New dependency risk assessed up front, not after a break:** JJWT needs a JSON provider (`jjwt-jackson` or `jjwt-gson`). Given Jackson 3's very recent, very disruptive package migration (M2), used `jjwt-gson` instead — Gson is a completely separate library, unaffected by anything Jackson-related. Verified `jjwt-gson` 0.13.0 is a real, current, actively-maintained artifact before committing to this choice.
- **Retired `ChannelRegistry` (M4's temporary stand-in), on schedule, not by accident.** `RetentionTrimmer` now sweeps every row in the real `channels` table instead. This closes spec §14 item 2 cleanly: since the webhook path now requires a real channel and a valid API key, there's no longer such a thing as an ad-hoc channel for the trimmer to miss.
- **Design decision, not explicitly specified in the original schema:** added `Channel.publicId` (a UUID) as the identifier that actually appears in every URL, keeping the auto-increment `id` purely internal. Sequential PKs in public APIs leak growth-rate information and make enumeration trivial — worth the small extra column even though the spec's original schema sketch didn't call it out.
- **Deliberate scope boundary, not an oversight:** `/ws/{channelId}`, replay, and filter-config endpoints remain unauthenticated after M5 — matches the roadmap's own explicit M5 ships list ("auth/data layer only"), tracked as spec §14 item 5, planned for M6.
- **Genuine open question flagged, not resolved by guessing:** whether `@WebFluxTest(WebhookController.class)` interacts with Spring Security's reactive auto-configuration for test slices — specifically whether it silently requires authentication for the whole slice, or ignores security entirely since `SecurityConfig` isn't explicitly imported. Not confident enough either way to add speculative fix code; if the pre-existing `WebhookControllerTest` cases fail after this milestone, this is the first thing to check.

