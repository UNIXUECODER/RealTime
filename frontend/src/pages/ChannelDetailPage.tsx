import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { AppShell } from '../components/AppShell'
import { CopyButton } from '../components/CopyButton'
import { EventPanel } from '../components/EventPanel'
import { TestEventComposer } from '../components/TestEventComposer'

export function ChannelDetailPage() {
  const { channelId } = useParams<{ channelId: string }>()
  const channelQuery = useQuery({
    queryKey: ['channels', channelId],
    queryFn: () => api.getChannel(channelId as string),
    enabled: Boolean(channelId),
  })

  if (channelQuery.isLoading) {
    return (
      <AppShell>
        <p className="text-sm text-text-muted">Loading…</p>
      </AppShell>
    )
  }

  if (channelQuery.isError || !channelQuery.data) {
    return (
      <AppShell>
        <p className="text-sm text-error">Couldn&rsquo;t load this channel.</p>
      </AppShell>
    )
  }

  const channel = channelQuery.data
  const webhookUrl = `${window.location.origin}/webhook/${channel.channelId}`
  const wsUrl = `${window.location.origin.replace(/^http/, 'ws')}/ws/${channel.channelId}`

  return (
    <AppShell>
      <h1 className="mb-6 text-lg font-medium text-text">{channel.name}</h1>

      <div className="mb-8 space-y-4 rounded-[5px] border border-border bg-surface p-4">
        <Field label="Webhook URL" value={webhookUrl} />
        <Field label="WebSocket URL" value={wsUrl} />
        {/* Not copyable — this is a masked display value, not a usable credential.
            The real key was shown once, at creation, and can't be retrieved again. */}
        <Field label="API key" value={`rtk_...${channel.apiKeySuffix}`} copyable={false} />
      </div>

      <div className="space-y-4">
        <EventPanel channelId={channel.channelId} />
        <TestEventComposer channelId={channel.channelId} />
      </div>
    </AppShell>
  )
}

function Field({ label, value, copyable = true }: { label: string; value: string; copyable?: boolean }) {
  return (
    <div>
      <div className="mb-1 text-sm text-text-muted">{label}</div>
      <div className="flex items-center gap-2">
        <code className="flex-1 truncate rounded-[3px] bg-ink px-2 py-1.5 font-mono text-xs text-text">{value}</code>
        {copyable && <CopyButton value={value} />}
      </div>
    </div>
  )
}
