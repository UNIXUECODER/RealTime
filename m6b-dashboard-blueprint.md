# M6b Dashboard — Technical Blueprint

Status: **planning, not yet implemented**. This is the design to review and sign off on before any code is written, in the same spirit as the roadmap's own milestone discipline — agree the shape first.

Supersedes the "Thymeleaf + HTMX" line in `realtime-roadmap.md`'s M6b entry (to be updated once this is approved — see §12).

---

## 1. Decision recap

Dashboard ships as a TypeScript SPA — **Vite + React + Tailwind** — not server-rendered Thymeleaf. Rationale: the channel-detail page's live event panel (auto-scroll, pause/resume, collapsible JSON tree, connection status, latency) is fundamentally client-owned continuous state, not server round-trips. HTMX's "server renders, client swaps" model is a good fit for the CRUD pages (login, signup, channel list) and a poor fit for the one page that's actually hard. React's component/hook model is the right tool specifically for that page.

The backend is **not** being rewritten or reframed around the frontend. Two small, additive backend changes are required regardless of which frontend stack was chosen (§5) — they're requirements gaps the roadmap's own wording exposed, not consequences of picking React.

---

## 2. Repository layout

```
realtime/
├── pom.xml
├── src/main/java/...          (untouched)
├── src/main/resources/
│   └── static/                 (NEW — Vite build output lands here, gitignored)
└── frontend/                   (NEW — the Vite project)
    ├── package.json
    ├── vite.config.ts
    ├── tailwind.config.ts
    ├── tsconfig.json
    └── src/
        ├── main.tsx
        ├── App.tsx              (router root)
        ├── api/
        │   ├── client.ts        (fetch wrapper, attaches Authorization header)
        │   └── types.ts         (mirrors ChannelDto, CreateChannelResponse, etc.)
        ├── auth/
        │   ├── AuthContext.tsx  (token state, login/logout)
        │   └── ProtectedRoute.tsx
        ├── hooks/
        │   └── useChannelSocket.ts   (the live-panel WS hook — see §8)
        ├── components/
        │   ├── JsonTreeView.tsx
        │   ├── CopyButton.tsx
        │   ├── ConnectionBadge.tsx
        │   └── EventRow.tsx
        └── pages/
            ├── LoginPage.tsx
            ├── SignupPage.tsx
            ├── ChannelListPage.tsx
            └── ChannelDetailPage.tsx
```

`frontend/` sits at the repo root, sibling to `pom.xml` — not nested under `src/main/`, so it reads clearly as "a separate project that happens to build into this one's static resources," not Java source.

---

## 3. Build & deployment integration

**Single deployable artifact, same as today.** Vite's `build.outDir` is configured to write directly to `../src/main/resources/static` (relative path from `frontend/`), so `npm run build` populates exactly what Spring Boot's WebFlux auto-configuration already serves as static content — **zero backend code needed for asset serving.**

Consequences:
- **No CORS configuration, anywhere.** SPA and API share an origin. This is the main simplification the single-artifact approach buys.
- `docker-compose.yml` stays a one-service file, as today.
- `Dockerfile` gains a Node build stage ahead of the existing Maven stage:
  ```dockerfile
  FROM node:22-slim AS frontend-build
  WORKDIR /frontend
  COPY frontend/package*.json ./
  RUN npm ci
  COPY frontend/ ./
  RUN npm run build   # writes into ../src/main/resources/static

  FROM maven:... AS build
  COPY --from=frontend-build /src/main/resources/static /app/src/main/resources/static
  # ...existing Maven build steps continue unchanged
  ```
- **Not** wiring `frontend-maven-plugin` into `pom.xml` for this milestone. That's real build-config surface area for a problem that doesn't exist yet — there's no CI to integrate with until M10. For now, `npm run build` is a documented manual step in the README's dev workflow (same category as "make sure `docker-compose up` is running" already is), and folding it into `mvn package` becomes part of M10 alongside the rest of the CI/CD setup.
- `src/main/resources/static/` is gitignored (build output, like `target/`).

---

## 4. Routing

**Client-side** (React Router), all under one path prefix so nothing collides with the existing JSON API:

| Path | Page |
|---|---|
| `/app/login` | Login form |
| `/app/signup` | Signup form |
| `/app/channels` | Channel list |
| `/app/channels/:id` | Channel detail — URLs, masked key, test-event, live panel |

**One small, unavoidable backend addition, independent of frontend stack:** a hard refresh on e.g. `/app/channels/abc123` needs to still return `index.html` (SPA fallback) rather than 404 — Spring's static handler won't do this on its own for an unmatched sub-path. A single route:

```java
@Controller
public class SpaController {
    @GetMapping("/app/**")
    public Mono<Resource> index() {
        return Mono.just(new ClassPathResource("static/index.html"));
    }
}
```

This is the one asterisk on "backend untouched" — it's routing/serving glue, not business logic, and would have been needed in some form under the Thymeleaf plan too (four routes there vs. one here).

---

## 5. Backend additions required (found during this planning pass)

Two real gaps, surfaced by actually checking what the current API can support — not stack-dependent, would exist under Thymeleaf too.

### 5.1 `POST /channels/{id}/test-event`

The roadmap's "send test event button" has no backing endpoint today. The dashboard can't use the real public `/webhook/{id}` path because the raw API key is shown once at creation and never retrievable again (by design — that's a deliberate security property, not a gap to route around).

Correct shape: a new endpoint on the *existing*, already JWT+ownership-authenticated `ChannelController` — not a new API-key-based path. It reuses the same `requireOwnedChannel` check already backing `GET`/`DELETE`, then calls `IngestService.ingest(channelId, rawBody)` directly, the exact method the real webhook path uses — so a test event goes through the same filter rules, dedup, and archival real events do. That's a feature: it lets someone validate their filter configuration by clicking a button, not just prove connectivity.

**One real design point:** `IngestService.ingest` dedups on a fingerprint of `(channelId, rawBody)` within a TTL window (`IngestService.java:79`). If "send test event" always fires an identical canned payload, the second click within that window returns `DUPLICATE`, not `ACCEPTED` — nothing would appear in the live panel, silently breaking the exact demo the roadmap's exit criteria describes ("click send test event... watch it appear... with you standing there saying nothing"). Fix: the endpoint injects a server-generated unique field (e.g. a UUID or the request timestamp) into the payload before calling `ingest()`, guaranteeing a distinct fingerprint on every click regardless of what the rest of the body contains.

Proposed contract:
```
POST /channels/{id}/test-event
Body (optional): { "message": "custom text" }   // merged into a canned envelope
Response: 202, same as the real webhook path
```
Default payload if body omitted: `{"type": "test.event", "message": "Hello from RealTime!", "sentAt": "<iso-timestamp>"}` — the `sentAt` field also doubles as the uniqueness field described above.

### 5.2 Masked API key display

`ChannelDto` (`channelId`, `name`, `createdAt`) carries no key information at all today — checked `ChannelService.toDto` directly. `CreateChannelResponse` includes the *raw* key, but only once, at creation. There is currently no way to show even a masked key (e.g. `rk_live_...a1b2`) on a channel visited after creation — which the roadmap's "masked API key" line requires.

Needed:
- New Flyway migration (`V4__add_api_key_suffix.sql`): `ALTER TABLE api_keys ADD COLUMN key_suffix VARCHAR(4) NOT NULL DEFAULT '????'` (or backfill logic if you'd rather not default — there's exactly one environment's data to worry about right now).
- `ChannelService.create` captures `rawApiKey.substring(rawApiKey.length() - 4)` before it's discarded, stores it in the new column alongside the hash. The raw key itself is never persisted or logged anywhere — only this trailing fragment, which is exactly the industry-standard pattern (Stripe, GitHub, AWS all display truncated key suffixes this way) precisely because a 4-character suffix doesn't meaningfully weaken the secret.
- `ChannelDto` gains `apiKeySuffix`; `toDto` does one additional `apiKeyRepository` lookup by `channelId` to populate it.

This does **not** touch `ApiKeyHasher`'s hashing or comparison logic — purely an additive, non-secret display field.

---

## 6. Auth flow

- Login/signup POST to the real `/auth/login` / `/auth/signup` (unchanged). On success, store the returned JWT in `localStorage` (not `sessionStorage` — a dashboard that logs you out on every refresh is bad UX, and the security delta between the two is marginal here: no third-party content is ever rendered, so the realistic XSS surface is self-authored bugs, not injected content).
- `api/client.ts` is a thin `fetch` wrapper that reads the token from `localStorage` and attaches `Authorization: Bearer <token>` to every request; a 401 response clears the token and redirects to `/app/login`.
- `AuthContext` exposes `{ token, login(), logout() }` to the tree; `ProtectedRoute` wraps the channel-list/detail routes and redirects unauthenticated visitors to login.
- The live panel's WS connection reads the same `localStorage` token to build `wss://.../ws/{channelId}?token=...` — identical mechanism M6a already built server-side, no new server auth code.
- Token expiry is 24h by default (`JwtService`, configurable) — long enough that a real refresh flow isn't warranted for an MVP dashboard; a 401 mid-session just bounces to login. Worth revisiting if this becomes more than a demo tool.

---

## 7. API client layer

Typed wrapper functions over the existing endpoints, mirroring their DTOs in `api/types.ts`:

```ts
login(email, password): Promise<{ token: string }>
signup(email, password, tenantName): Promise<{ token: string }>
listChannels(): Promise<ChannelDto[]>
getChannel(id): Promise<ChannelDto>
createChannel(name): Promise<CreateChannelResponse>
deleteChannel(id): Promise<void>
sendTestEvent(id, body?): Promise<void>
```

**TanStack Query** wraps the list/detail/create/delete calls — standard, low-risk choice for what's genuinely just REST CRUD with loading/error states. Keeps custom code focused on the one thing that actually needs bespoke logic: the WS hook.

---

## 8. The live event panel — state design

A single hook, `useChannelSocket(channelId: string)`, owns all of it:

```ts
function useChannelSocket(channelId: string) {
  // returns: { status, events, isPaused, pause(), resume(), clear() }
}
```

Internals:
- **Connection status** — `'connecting' | 'open' | 'closed' | 'error'`, updated from the native `WebSocket`'s own lifecycle callbacks.
- **Pause/resume** — incoming frames always get parsed and timestamped on arrival; while paused, they're pushed into a `useRef` queue instead of `useState`, so the visible list and scroll position genuinely don't move. `resume()` flushes the queue into state in one batch.
- **Auto-scroll** — a ref on the scroll container; before appending, check whether the user is already within a small threshold of the bottom (`scrollHeight - scrollTop - clientHeight < N`). Only auto-scroll if they were — so scrolling up to inspect an old event doesn't get yanked away from under them.
- **Latency** — each frame carries `received_at` from the server already (`IngestService.appendToStream`, confirmed — no backend change needed). Latency shown per-event is `Date.now() - Date.parse(receivedAt)`.
- **Clear** — just empties the visible array; doesn't touch the connection or the server-side stream.

No Redux/Zustand for this milestone — one hook, one consuming page, no cross-page state-sharing need yet. Straightforward to lift into a shared store later if M7's per-connection filter UI ends up needing to coordinate with it.

---

## 9. Components

- `JsonTreeView` — small custom recursive component (~50 lines), not a third-party lib (`react-json-view` and similar ship their own CSS that fights Tailwind, and the recursion itself is genuinely simple to own).
- `CopyButton` — `navigator.clipboard.writeText()` + a brief "Copied" state, reused for webhook URL / WS URL / key suffix display.
- `ConnectionBadge` — small colored dot + label, driven by `useChannelSocket`'s `status`.
- `EventRow` — one live-panel entry: timestamp, latency, `JsonTreeView` for the payload, collapsed by default for anything past the first few events (long lists of fully-expanded trees get unreadable fast).

---

## 10. Styling

Tailwind, dark theme — fits a "live inspector/terminal" product far better than a light CRUD-app default, and differentiates it from looking like generic scaffolding. Monospace font for the event panel specifically (payloads, IDs, timestamps); regular sans-serif elsewhere (nav, forms, buttons). Exact palette/type choices to be finalized when we're actually building the pages, not blocked on anything above.

---

## 11. Testing

Given the backend's test discipline (56 tests, real assertions, not scaffolding), the frontend shouldn't ship with zero coverage. Proposed baseline, kept light for MVP scope:
- **Vitest** + **React Testing Library**, already the standard pairing for a Vite project.
- Unit tests for `useChannelSocket`'s pause/resume/auto-scroll-threshold logic — this is the one piece of genuinely non-trivial client logic, and the place a regression would be least visible without a test.
- A smoke test for `JsonTreeView` (renders nested objects/arrays correctly, expand/collapse toggles).
- Explicitly **not** aiming for E2E/Playwright coverage at this milestone — that's a reasonable M10 (CI/CD) addition once there's a pipeline to run it in.

---

## 12. Doc updates once this is approved

- `realtime-roadmap.md`, M6b "Ships" line: replace "Thymeleaf + HTMX pages" with the React/Vite stack description, and add the two backend additions (§5) as explicit ships-items so they're not invisible scope.
- `realtime-architecture-spec.md`: the masked-key storage decision (§5.2) is exactly the kind of thing worth a line wherever the spec documents the API-key security model, so a future reader understands why a suffix is stored at all.
- `realtime-build-log.md`: once implemented, an entry describing this planning pass and the two gaps it surfaced — consistent with how M6a's build-log entry already documents "found during review, not part of the original plan" items.

---

## 13. Open items I'd like your call on before writing code

1. **Test-event payload** — fixed canned payload only, or a small editable textarea (prefilled with the default, user can tweak `message`/add fields) so the button doubles as a way to test filter rules interactively? I lean toward the editable version — cheap to add, meaningfully more useful — but it's your call on MVP scope.
2. **`api_keys.key_suffix` backfill** — since this is presumably a fresh dev database with no real customer keys yet, a `DEFAULT '????'` on the migration is harmless. Confirm that's true (no data you care about preserving) before I write it that way.
3. Anything in §2's file layout or §6's auth approach you'd want to shape differently before I start scaffolding.
