import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { EventRow } from './EventRow'
import type { StreamEvent } from '../hooks/useChannelSocket'

describe('EventRow', () => {
  it('renders valid time and numeric latency even with 9-digit nanosecond timestamps', () => {
    // Java Instant.now().toString() outputs 9 decimal places of nanosecond precision
    const nanoTimestamp = '2026-09-15T05:53:08.060092561Z'
    const event: StreamEvent = {
      id: '1789451588061-0',
      payload: { type: 'order.created', amount: 100 },
      rawPayload: '{"type":"order.created","amount":100}',
      receivedAt: nanoTimestamp,
      arrivedAt: Date.parse('2026-09-15T05:53:08.100Z'),
    }

    render(<EventRow event={event} defaultExpanded={false} />)

    // Latency must NOT be NaNms
    const latencyEl = screen.getByText(/\d+ms/)
    expect(latencyEl).toBeInTheDocument()
    expect(screen.queryByText(/NaNms/i)).not.toBeInTheDocument()

    // Time must NOT be Invalid Date
    expect(screen.queryByText(/Invalid Date/i)).not.toBeInTheDocument()
    expect(screen.getByText('order.created')).toBeInTheDocument()
  })

  it('renders standard 3-digit millisecond timestamps without alteration', () => {
    const milliTimestamp = '2026-09-15T05:53:08.060Z'
    const event: StreamEvent = {
      id: '1-0',
      payload: { type: 'ping' },
      rawPayload: '{"type":"ping"}',
      receivedAt: milliTimestamp,
      arrivedAt: Date.parse('2026-09-15T05:53:08.085Z'),
    }

    render(<EventRow event={event} defaultExpanded={false} />)

    expect(screen.getByText('25ms')).toBeInTheDocument()
    expect(screen.getByText('ping')).toBeInTheDocument()
  })

  it('gracefully falls back to 0ms and dash when timestamp is unparseable', () => {
    const event: StreamEvent = {
      id: '2-0',
      payload: { type: 'corrupt' },
      rawPayload: '{"type":"corrupt"}',
      receivedAt: 'not-a-valid-date',
      arrivedAt: Date.now(),
    }

    render(<EventRow event={event} defaultExpanded={false} />)

    expect(screen.getByText('0ms')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.queryByText(/NaNms/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Invalid Date/i)).not.toBeInTheDocument()
  })
})
