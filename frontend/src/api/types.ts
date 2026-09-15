// Mirrors the backend's actual response records exactly — see the referenced Java
// files for the source of truth. Kept intentionally flat/dumb: this file has no
// business logic, just shape.

/** dev.realtime.auth.AuthResponse */
export interface AuthResponse {
  token: string
}

/** dev.realtime.tenancy.ChannelDto — apiKeySuffix is the last 4 chars only, for
 * masked display (e.g. "rtk_...a1b2"); the full key is never retrievable after
 * creation, by design. */
export interface ChannelDto {
  channelId: string
  name: string
  createdAt: string
  apiKeySuffix: string
}

/** dev.realtime.tenancy.CreateChannelResponse — apiKey is the raw key, shown exactly
 * once, here, at creation. It is never returned by any other endpoint again. */
export interface CreateChannelResponse {
  channelId: string
  name: string
  apiKey: string
}

/** dev.realtime.ingest.IngestOutcome, via dev.realtime.tenancy.TestEventResponse */
export type IngestOutcome = 'ACCEPTED' | 'FILTERED' | 'DUPLICATE'

export interface TestEventResponse {
  outcome: IngestOutcome
}

/** dev.realtime.web.ErrorResponse — the shape GlobalErrorHandler puts on every
 * non-2xx response. */
export interface ApiErrorBody {
  requestId: string
  status: number
  error: string
  message: string
  timestamp: string
}
