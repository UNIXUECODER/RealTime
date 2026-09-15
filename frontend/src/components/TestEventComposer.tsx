import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type { IngestOutcome } from '../api/types'

function defaultPayload(): string {
  return JSON.stringify(
    { type: 'test.event', message: 'Hello from RealTime!', sentAt: new Date().toISOString() },
    null,
    2,
  )
}

// A fresh `sentAt` on every default/reset means clicking Send on the untouched
// default never collides with the ingest pipeline's own dedup window. Editing the
// body and deliberately resubmitting identical JSON is a legitimate way to test that
// dedup actually works — the real DUPLICATE outcome below reflects that honestly,
// rather than a server-side workaround hiding it.
const OUTCOME_COPY: Record<IngestOutcome, { label: string; tone: string }> = {
  ACCEPTED: { label: 'Sent — check the panel above', tone: 'text-success' },
  FILTERED: { label: "Filtered — didn't match this channel's rules", tone: 'text-filtered' },
  DUPLICATE: { label: 'Duplicate — identical payload was already sent recently', tone: 'text-duplicate' },
}

export function TestEventComposer({ channelId }: { channelId: string }) {
  const [body, setBody] = useState(defaultPayload)
  const [jsonError, setJsonError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (payload: string) => api.sendTestEvent(channelId, payload),
  })

  function handleReset() {
    setBody(defaultPayload())
    setJsonError(null)
    mutation.reset()
  }

  function handleChange(value: string) {
    setBody(value)
    try {
      JSON.parse(value)
      setJsonError(null)
    } catch {
      setJsonError('Not valid JSON')
    }
  }

  const outcome = mutation.data?.outcome
  const outcomeCopy = outcome ? OUTCOME_COPY[outcome] : null

  return (
    <div className="rounded-[5px] border border-border bg-surface p-4">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-text">Send a test event</h2>
        <button type="button" onClick={handleReset} className="text-xs text-text-muted hover:text-text">
          Reset to default
        </button>
      </div>
      <textarea
        value={body}
        onChange={(event) => handleChange(event.target.value)}
        rows={5}
        spellCheck={false}
        className="w-full resize-none rounded-[3px] border border-border bg-ink px-3 py-2 font-mono text-xs text-text outline-none focus:border-signal"
      />
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={() => mutation.mutate(body)}
          disabled={Boolean(jsonError) || mutation.isPending}
          className="rounded-[3px] bg-signal px-3 py-1.5 text-sm font-medium text-ink transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {mutation.isPending ? 'Sending…' : 'Send'}
        </button>
        {jsonError && <p className="text-xs text-error">{jsonError}</p>}
        {outcomeCopy && <p className={`text-xs ${outcomeCopy.tone}`}>{outcomeCopy.label}</p>}
        {mutation.isError && (
          <p className="text-xs text-error">
            {mutation.error instanceof ApiError ? mutation.error.message : 'Failed to send'}
          </p>
        )}
      </div>
    </div>
  )
}
