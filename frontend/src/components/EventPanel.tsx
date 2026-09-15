import { useEffect, useRef } from 'react'
import { useChannelSocket } from '../hooks/useChannelSocket'
import { ConnectionBadge } from './ConnectionBadge'
import { EventRow } from './EventRow'

const AUTO_SCROLL_THRESHOLD_PX = 48

/** Auto-scroll lives here, not in useChannelSocket — it needs a ref to the actual
 * scrollable DOM node, which only a component holds. The hook stays pure data/state. */
export function EventPanel({ channelId }: { channelId: string }) {
  const { status, events, isPaused, pausedCount, pause, resume, clear } = useChannelSocket(channelId)
  const scrollRef = useRef<HTMLDivElement>(null)
  const wasNearBottomRef = useRef(true)

  function handleScroll() {
    const el = scrollRef.current
    if (!el) return
    wasNearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < AUTO_SCROLL_THRESHOLD_PX
  }

  useEffect(() => {
    const el = scrollRef.current
    // Only follow new events if the person was already near the bottom — scrolling up
    // to inspect an older event shouldn't get yanked away from under them.
    if (el && wasNearBottomRef.current) {
      el.scrollTop = el.scrollHeight
    }
  }, [events.length])

  return (
    <div className="rounded-[5px] border border-border bg-surface">
      <div className="flex items-center justify-between border-b border-border px-3 py-2">
        <ConnectionBadge status={status} />
        <div className="flex items-center gap-2">
          {isPaused && pausedCount > 0 && <span className="text-xs text-text-muted">{pausedCount} new</span>}
          <button
            type="button"
            onClick={isPaused ? resume : pause}
            className="rounded-[3px] border border-border px-2 py-1 text-xs text-text-muted hover:border-signal hover:text-text"
          >
            {isPaused ? 'Resume' : 'Pause'}
          </button>
          <button
            type="button"
            onClick={clear}
            className="rounded-[3px] border border-border px-2 py-1 text-xs text-text-muted hover:border-signal hover:text-text"
          >
            Clear
          </button>
        </div>
      </div>

      <div ref={scrollRef} onScroll={handleScroll} className="max-h-96 overflow-y-auto">
        {events.length === 0 && (
          <p className="p-4 text-center text-sm text-text-muted">
            Waiting for events&hellip; send a test event below, or point a webhook at this channel.
          </p>
        )}
        {events.map((event, index) => (
          <EventRow key={event.id} event={event} defaultExpanded={index >= events.length - 3} />
        ))}
      </div>
    </div>
  )
}
