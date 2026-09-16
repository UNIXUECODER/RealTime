# M7 — Per-Connection Filtering: Technical Blueprint

Status: **planning, not yet implemented**. Same discipline as the M6b blueprint — agree the shape before writing code.

Roadmap scope (`realtime-roadmap.md`):
> **Ships:** WS Gateway accepts a filter (query param or post-connect control frame) using the same AND-only DSL as channel filters (spec §5), evaluated per-connection before emitting each frame.
> **Explicitly not in scope:** Persisting connection filters, OR/nested rule logic.
> **Exit criteria:** Two WS clients (or two dashboard tabs) connect to the same channel with different filters; send a mixed batch of events; confirm each client receives only the events matching its own filter.

---

## 1. How the filter travels to the server: query param, not a control frame

Spec §5 leaves this open ("query params **or** a post-connect control frame"). Recommending **query param**, for a concrete reason, not just "simpler":

A post-connect control frame means the connection is open and could start emitting events *before* the filter is known — either buffer everything until the frame arrives (new state, new edge cases: what if it never arrives? what's the buffer cap?), or accept a brief window where the first few events ignore the filter. A query param is known at handshake time, before `stream()` ever starts reading — zero race window, and `ChannelWebSocketHandler.handle()` already reads `?token=` and `?last_id=` exactly this way. This is the same shape of decision as M5's `?token=` choice: browsers can't do custom WS headers, so query params are already the established mechanism here.

The control-frame approach isn't wrong — it's the natural extension if "change your filter without reconnecting" ever becomes a real requirement — but that's not in M7's exit criteria, and building the buffering/timeout machinery for it now would be exactly the kind of speculative complexity the spec's own filtering section explicitly argues against for OR/nested logic. Named and deferred, not silently dropped.

**Format:** a JSON-encoded array of the *exact same* `FilterRule` shape already used for channel-level filters — `?filter=%5B%7B%22field%22%3A%22type%22%2C%22op%22%3A%22%3D%3D%22%2C%22value%22%3A%22payment.failed%22%7D%5D` (URL-encoded `[{"field":"type","op":"==","value":"payment.failed"}]`). Reusing the identical shape means zero new DSL, zero new parser — just `objectMapper.readValue(param, new TypeReference<List<FilterRule>>() {})`. Absent param = no filter = matches everything, identical to how an empty channel-filter list already behaves in `FilterEngine.matches`.

---

## 2. Validation and the new close code

`FilterEngine.validate(List<FilterRule>)` already exists (built for the M6a filter-op bug) and already throws on an unsupported operator. Reusing it here directly: parse the query param, call `validate()`, and if either parsing or validation fails, close the socket rather than silently ignoring a bad filter or letting an exception surface mid-stream.

Precedent: `4401` for unauthorized (`ChannelAccessService`, M6a). Proposing **`4400`** for a malformed/invalid filter — same numbering logic as HTTP status codes, and it starts a discoverable convention (`4400` = bad request, `4401` = unauthorized, presumably `4429` = rate limited when M8 adds connection-level rate limiting). Small thing, but worth being deliberate about now rather than picking an arbitrary number per feature.

Proposing this parsing+validation live in a small, focused, independently unit-testable class — `ConnectionFilterResolver` or similar — rather than inline in the handler, matching the established pattern in this codebase (`JsonValidator`, `Fingerprint`, `StreamIds` are all small single-purpose classes with their own direct unit tests, not logic buried inside a handler that's hard to exercise in isolation). Given `ChannelWebSocketHandler` is already flagged in its own Javadoc as "the highest-risk code in the project," adding new logic to it as a bare inline block rather than a testable unit would be moving the wrong direction.

---

## 3. Where evaluation happens

Per spec §5: "evaluated in-memory against each event before emission — no extra Redis round-trip since the event's already in hand." Concretely, one line inserted into the existing pipeline:

```java
Flux<WebSocketMessage> outbound = tail(streamKey, lastId)
        .filter(record -> filterEngine.matches(record.getValue().get("payload"), connectionFilter))
        .map(this::toFrame)
        .map(session::textMessage);
```

`FilterEngine.matches(String, List<FilterRule>)` already exists, unchanged, already handles the empty/null-list "match everything" case. This is the main reason the query-param design is low-risk: the actual filtering logic isn't new code at all, just a new caller of code that's already been through M3 and M6a's validation passes.

---

## 4. Channel filters and connection filters don't need to be "combined" — they already compose

Channel-level filters run at ingest, before `XADD` — an event a channel filter rejects never enters the stream at all, so a connection filter downstream never sees it regardless of what it asks for. Connection filters run at emit time, per-socket, only ever narrowing further within whatever the channel already let through. No new logic ties these together; they compose for free because they operate at different points in the pipeline. Worth stating this plainly in the blueprint so it's understood rather than assumed, since it's easy to picture (incorrectly) two filter lists needing to be merged somewhere.

---

## 5. Closing a real, pre-existing gap while we're in this file

`ChannelWebSocketHandler` has **zero automated tests today** — the class's own Javadoc says outright: *"the highest-risk code in the project so far... verify this for real; don't take 'it compiles' as evidence it's correct."* M2's exit criteria was manual verification. That was a reasonable call for M2's scope, but M7 is about to add new logic to exactly this file, and the exit criteria we're targeting — "two connections, different filters, each gets only its own matching events" — is precisely the kind of concurrent, timing-sensitive scenario that's dangerous to verify by eyeballing two browser tabs and easy to get subtly wrong under real load.

Proposing M7 include the first real integration test for this handler: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + a real `ReactorNettyWebSocketClient` connecting against the actual running server (not a mocked handler) — covering:
1. No filter → client receives every event.
2. A filter set → client receives only matching events, others silently dropped for that connection.
3. **The actual exit-criteria scenario**: two simultaneous connections to the same channel, different filters, one mixed batch of events, each connection asserted to receive only its own matching subset.
4. Malformed filter → connection closes with `4400`.

Flagging honestly: WebSocket integration tests are genuinely fiddly to get right on the first try (async timing, `StepVerifier`/`Sinks` coordination, avoiding flakiness) — same caveat as always applies here, amplified: I can't compile or run this in my sandbox (no Maven Central access), and this specific test is more likely than the others to need a real debugging pass on your end rather than working perfectly from a patch file sight-unseen. Worth treating this one as "build together, iteratively" rather than "hand over and verify," if that's alright with you.

---

## 6. Explicit scope boundary: no dashboard UI for this in M7

The exit criteria says "two WS clients (**or** two dashboard tabs)" — read as "however you want to verify it," not "the dashboard must grow a filter-input feature." Given the earlier decision to defer all dashboard work until the polish pass before M10, I'm treating "add a connection-filter UI to the live panel" as explicitly out of scope for M7, verified instead via the automated test above plus a raw WS client (a small script, or `websocat`) for a manual sanity check. If this reads differently to you — i.e., you want the live panel to actually expose this — that's a real, separate scope decision worth naming rather than assuming either way.

---

## 7. Doc consequences once this ships

- **Spec §5**: currently states the choice as open ("query params **or** control frame"). Tighten to state which was actually built and why, matching the style of the existing "Resolved: AND-only rule list..." paragraph in the same section.
- **Roadmap**: mark M7 shipped.
- **Build log**: new M7 section — the query-param-vs-control-frame decision, the `4400` convention, and closing the WS handler test-coverage gap all belong there, same as every other milestone's build-log entry.

---

## 8. Open items for your call before I start

1. **Query param over control frame** — the recommendation above, but worth an explicit yes given it's the one real protocol-design decision here.
2. **`4400` for malformed filter** — fine as the convention, or did you have a different close-code scheme in mind?
3. **The WS integration test** — comfortable with "build and debug together" framing rather than "patch file, trust it blind," given this is the one place in this milestone I'm least able to self-verify?
4. **No dashboard UI in M7** — confirm that's the right read of "two dashboard tabs" in the exit criteria, not an assumption you'd push back on.
