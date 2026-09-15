import { useState } from 'react'
import { JsonTreeView } from './JsonTreeView'
import type { StreamEvent } from '../hooks/useChannelSocket'

function summarize(payload: unknown): string {
  if (typeof payload === 'object' && payload !== null && 'type' in (payload as Record<string, unknown>)) {
    return String((payload as Record<string, unknown>).type)
  }
  return 'event'
}

export function EventRow({ event, defaultExpanded }: { event: StreamEvent; defaultExpanded: boolean }) {
  const [isExpanded, setIsExpanded] = useState(defaultExpanded)

  // Java Instant.now().toString() outputs 9 decimal places of nanosecond precision.
  // ECMA-262 §21.4.1.15 specifies max 3 fractional digits (.sss); strict engines
  // (Safari / WebKit) can fail to parse 9 digits and return NaN / Invalid Date.
  // Truncate fractional seconds to 3 digits to ensure universal browser compatibility.
  const safeReceivedAt = event.receivedAt.replace(/(\.\d{3})\d+/, (_, m) => m)
  const parsedReceivedAt = Date.parse(safeReceivedAt)
  const latencyMs = Number.isNaN(parsedReceivedAt) ? 0 : Math.max(0, event.arrivedAt - parsedReceivedAt)
  const timeString = Number.isNaN(parsedReceivedAt) ? '—' : new Date(safeReceivedAt).toLocaleTimeString()

  return (
    <div className="animate-sweep-in border-b border-border px-3 py-2 font-mono text-xs last:border-b-0">
      <button
        type="button"
        onClick={() => setIsExpanded((prev) => !prev)}
        className="flex w-full items-center gap-3 text-left"
      >
        <span className="text-text-muted">{timeString}</span>
        <span className="w-14 text-text-muted">{latencyMs}ms</span>
        <span className="flex-1 truncate text-text">{summarize(event.payload)}</span>
        <span className="text-text-muted">{isExpanded ? '\u2212' : '+'}</span>
      </button>
      {isExpanded && (
        <div className="mt-2 pl-1">
          <JsonTreeView data={event.payload} />
        </div>
      )}
    </div>
  )
}
