import type {
  ApiErrorBody,
  AuthResponse,
  ChannelDto,
  CreateChannelResponse,
  TestEventResponse,
} from './types'

const TOKEN_KEY = 'realtime.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/** Thrown for any non-2xx response. `body` is null when the server didn't send a
 * JSON error body at all (e.g. a proxy-level failure), so callers should always
 * handle that case rather than assuming `body.message` exists. */
export class ApiError extends Error {
  status: number
  body: ApiErrorBody | null

  constructor(status: number, body: ApiErrorBody | null) {
    super(body?.message ?? `Request failed with status ${status}`)
    this.status = status
    this.body = body
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken()
  const headers = new Headers(init?.headers)
  headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(path, { ...init, headers })

  if (response.status === 401) {
    // Session gone (never issued, expired, or the server restarted with a new
    // signing key) — bounce to login rather than showing a confusing error deeper
    // in the page. See blueprint §6: no refresh flow at this milestone, tokens are
    // 24h by default, a 401 mid-session just means log in again.
    clearToken()
    window.location.href = '/app/login'
    throw new ApiError(401, null)
  }

  if (!response.ok) {
    let body: ApiErrorBody | null = null
    try {
      body = await response.json()
    } catch {
      // Non-JSON error body (rare — a proxy/gateway error page, say). Leave body
      // null; ApiError falls back to a generic message in that case.
    }
    throw new ApiError(response.status, body)
  }

  // Handles both a genuinely empty body (delete endpoints) and a populated one
  // uniformly, rather than special-casing on status code.
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  login: (email: string, password: string) =>
    request<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  signup: (tenantName: string, email: string, password: string) =>
    request<AuthResponse>('/auth/signup', {
      method: 'POST',
      body: JSON.stringify({ tenantName, email, password }),
    }),

  listChannels: () => request<ChannelDto[]>('/channels'),

  getChannel: (channelId: string) => request<ChannelDto>(`/channels/${channelId}`),

  createChannel: (name: string) =>
    request<CreateChannelResponse>('/channels', {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),

  deleteChannel: (channelId: string) => request<void>(`/channels/${channelId}`, { method: 'DELETE' }),

  sendTestEvent: (channelId: string, payload: string) =>
    request<TestEventResponse>(`/channels/${channelId}/test-event`, {
      method: 'POST',
      // payload is already-valid JSON text from the composer — sent as-is as the
      // raw request body, matching ChannelController.sendTestEvent's
      // @RequestBody Mono<String>, not wrapped or re-encoded.
      body: payload,
    }),
}
