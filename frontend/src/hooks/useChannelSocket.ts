import { useCallback, useEffect, useRef, useState } from 'react'
import { getToken } from '../api/client'

export type ConnectionStatus = 'connecting' | 'open' | 'closed' | 'error' | 'unauthorized'

export interface StreamEvent {
  id: string
  /** Parsed JSON payload, for the tree viewer. Falls back to the raw string on the
   * rare chance it isn't valid JSON (shouldn't happen — ingest validates this before
   * a payload ever reaches the stream — but a render crash is worse than a fallback). */
  payload: unknown
  rawPayload: string
  /** Server-side ingest timestamp (IngestService.appendToStream's `received_at`) — the
   * basis for the per-event latency shown in the UI. */
  receivedAt: string
  /** Client-side arrival time, for the latency calculation. */
  arrivedAt: number
}

/** Exact shape ChannelWebSocketHandler.toFrame serializes. `payload` here is itself a
 * JSON *string* (the raw webhook body, stored and forwarded as-is) — a real double
 * decode, not a nested object; easy to get wrong without checking the Java source
 * directly, which is what this comment is here to prevent re-litigating. */
interface WireFrame {
  id: string
  payload: string
  received_at: string
}

interface UseChannelSocketResult {
  status: ConnectionStatus
  events: StreamEvent[]
  isPaused: boolean
  pausedCount: number
  pause: () => void
  resume: () => void
  clear: () => void
}

/**
 * Owns the live connection and its data — nothing about scrolling or rendering.
 * Auto-scroll needs a ref to the actual DOM node, which only the component holds, so
 * it's kept out of this hook deliberately (see ChannelDetailPage's EventPanel).
 */
export function useChannelSocket(channelId: string): UseChannelSocketResult {
  const [status, setStatus] = useState<ConnectionStatus>('connecting')
  const [events, setEvents] = useState<StreamEvent[]>([])
  const [isPaused, setIsPaused] = useState(false)
  const [pausedCount, setPausedCount] = useState(0)

  const pausedQueueRef = useRef<StreamEvent[]>([])
  const isPausedRef = useRef(false)

  useEffect(() => {
    isPausedRef.current = isPaused
  }, [isPaused])

  useEffect(() => {
    const token = getToken()
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/ws/${channelId}?token=${encodeURIComponent(token ?? '')}`

    setStatus('connecting')
    setEvents([])
    pausedQueueRef.current = []
    setPausedCount(0)

    const socket = new WebSocket(url)

    socket.onopen = () => setStatus('open')

    socket.onmessage = (event: MessageEvent<string>) => {
      let frame: WireFrame
      try {
        frame = JSON.parse(event.data)
      } catch {
        return // Malformed frame — drop it rather than crash the whole panel.
      }

      let payload: unknown = frame.payload
      try {
        payload = JSON.parse(frame.payload)
      } catch {
        // Shouldn't happen (ingest validates JSON before it's ever stored), but fall
        // back to the raw string rather than losing the event.
      }

      const streamEvent: StreamEvent = {
        id: frame.id,
        payload,
        rawPayload: frame.payload,
        receivedAt: frame.received_at,
        arrivedAt: Date.now(),
      }

      if (isPausedRef.current) {
        pausedQueueRef.current.push(streamEvent)
        setPausedCount((count) => count + 1)
      } else {
        setEvents((prev) => [...prev, streamEvent])
      }
    }

    // 4401 is ChannelAccessService's own close code for a rejected/expired token
    // (see ChannelWebSocketHandler) — distinguishing it from a generic drop means the
    // UI can point someone at logging in again instead of just "disconnected".
    socket.onclose = (event) => setStatus(event.code === 4401 ? 'unauthorized' : 'closed')
    socket.onerror = () => setStatus('error')

    return () => socket.close()
  }, [channelId])

  const pause = useCallback(() => setIsPaused(true), [])

  const resume = useCallback(() => {
    // Snapshot and clear the ref BEFORE scheduling the state update — setEvents's
    // updater closure reads pausedQueueRef.current lazily, whenever React actually
    // invokes it, not at the moment setEvents() is called. Clearing the ref on the
    // very next line (as this used to) raced against that: React sometimes hadn't run
    // the updater yet, so it saw an already-emptied queue. Capturing `queued` as a
    // plain local avoids the closure depending on a ref's value at some later,
    // indeterminate time.
    const queued = pausedQueueRef.current
    pausedQueueRef.current = []
    setIsPaused(false)
    setEvents((prev) => [...prev, ...queued])
    setPausedCount(0)
  }, [])

  const clear = useCallback(() => {
    setEvents([])
    pausedQueueRef.current = []
    setPausedCount(0)
  }, [])

  return { status, events, isPaused, pausedCount, pause, resume, clear }
}
