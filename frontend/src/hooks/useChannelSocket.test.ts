import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useChannelSocket } from './useChannelSocket'

class MockWebSocket {
  static instances: MockWebSocket[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: ((event: { code: number }) => void) | null = null
  onerror: (() => void) | null = null
  url: string
  closed = false

  constructor(url: string) {
    this.url = url
    MockWebSocket.instances.push(this)
  }

  close() {
    this.closed = true
  }

  emitMessage(data: string) {
    this.onmessage?.({ data })
  }
}

function frame(payload: unknown, id = '1-0', receivedAt = new Date().toISOString()) {
  return JSON.stringify({ id, payload: JSON.stringify(payload), received_at: receivedAt })
}

beforeEach(() => {
  MockWebSocket.instances = []
  vi.stubGlobal('WebSocket', MockWebSocket)
  localStorage.setItem('realtime.token', 'test-token')
})

describe('useChannelSocket', () => {
  it('appends events as they arrive while not paused', () => {
    const { result } = renderHook(() => useChannelSocket('ch1'))
    const socket = MockWebSocket.instances[0]

    act(() => socket.emitMessage(frame({ type: 'a' })))

    expect(result.current.events).toHaveLength(1)
    expect(result.current.events[0].payload).toEqual({ type: 'a' })
  })

  it('queues events while paused instead of appending them', () => {
    const { result } = renderHook(() => useChannelSocket('ch1'))
    const socket = MockWebSocket.instances[0]

    act(() => result.current.pause())
    act(() => socket.emitMessage(frame({ type: 'b' })))

    expect(result.current.events).toHaveLength(0)
    expect(result.current.pausedCount).toBe(1)
    expect(result.current.isPaused).toBe(true)
  })

  it('flushes the paused queue on resume, in arrival order', () => {
    const { result } = renderHook(() => useChannelSocket('ch1'))
    const socket = MockWebSocket.instances[0]

    act(() => result.current.pause())
    act(() => socket.emitMessage(frame({ type: 'first' }, '1-0')))
    act(() => socket.emitMessage(frame({ type: 'second' }, '2-0')))
    act(() => result.current.resume())

    expect(result.current.events).toHaveLength(2)
    expect(result.current.events[0].payload).toEqual({ type: 'first' })
    expect(result.current.events[1].payload).toEqual({ type: 'second' })
    expect(result.current.pausedCount).toBe(0)
    expect(result.current.isPaused).toBe(false)
  })

  it('clear empties events without touching the connection', () => {
    const { result } = renderHook(() => useChannelSocket('ch1'))
    const socket = MockWebSocket.instances[0]

    act(() => socket.emitMessage(frame({ type: 'a' })))
    act(() => result.current.clear())

    expect(result.current.events).toHaveLength(0)
    expect(socket.closed).toBe(false)
  })

  it('treats close code 4401 as unauthorized, distinct from a generic close', () => {
    const { result } = renderHook(() => useChannelSocket('ch1'))
    const socket = MockWebSocket.instances[0]

    act(() => socket.onclose?.({ code: 4401 }))

    expect(result.current.status).toBe('unauthorized')
  })
})
