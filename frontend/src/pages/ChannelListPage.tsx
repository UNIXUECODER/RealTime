import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { AppShell } from '../components/AppShell'

export function ChannelListPage() {
  const queryClient = useQueryClient()
  const [newChannelName, setNewChannelName] = useState('')

  const channelsQuery = useQuery({ queryKey: ['channels'], queryFn: api.listChannels })

  const createMutation = useMutation({
    mutationFn: (name: string) => api.createChannel(name),
    onSuccess: () => {
      setNewChannelName('')
      queryClient.invalidateQueries({ queryKey: ['channels'] })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (channelId: string) => api.deleteChannel(channelId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['channels'] }),
  })

  function handleCreate(event: FormEvent) {
    event.preventDefault()
    if (newChannelName.trim()) {
      createMutation.mutate(newChannelName.trim())
    }
  }

  return (
    <AppShell>
      <h1 className="mb-6 text-lg font-medium text-text">Channels</h1>

      <form onSubmit={handleCreate} className="mb-6 flex gap-2">
        <input
          type="text"
          placeholder="Channel name"
          value={newChannelName}
          onChange={(event) => setNewChannelName(event.target.value)}
          className="flex-1 rounded-[3px] border border-border bg-surface px-3 py-2 text-sm text-text outline-none focus:border-signal"
        />
        <button
          type="submit"
          disabled={createMutation.isPending}
          className="rounded-[3px] bg-signal px-4 py-2 text-sm font-medium text-ink transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {createMutation.isPending ? 'Creating…' : 'Create channel'}
        </button>
      </form>

      {createMutation.isSuccess && (
        <div className="mb-6 rounded-[5px] border border-signal-dim bg-surface p-4">
          <p className="mb-2 text-sm text-text">
            <strong className="font-medium">{createMutation.data.name}</strong> created. Copy its API key now
            — it won&rsquo;t be shown again.
          </p>
          <code className="block break-all rounded-[3px] bg-ink px-2 py-1.5 font-mono text-xs text-signal">
            {createMutation.data.apiKey}
          </code>
        </div>
      )}

      {channelsQuery.isLoading && <p className="text-sm text-text-muted">Loading…</p>}
      {channelsQuery.isError && <p className="text-sm text-error">Couldn&rsquo;t load channels.</p>}

      {channelsQuery.data && channelsQuery.data.length === 0 && (
        <p className="text-sm text-text-muted">No channels yet — create one above to get started.</p>
      )}

      {channelsQuery.data && channelsQuery.data.length > 0 && (
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-border text-text-muted">
              <th className="pb-2 font-normal">Name</th>
              <th className="pb-2 font-normal">Channel ID</th>
              <th className="pb-2 font-normal">Key</th>
              <th className="pb-2 font-normal">Created</th>
              <th className="pb-2 font-normal" />
            </tr>
          </thead>
          <tbody>
            {channelsQuery.data.map((channel) => (
              <tr key={channel.channelId} className="border-b border-border">
                <td className="py-2.5">
                  <Link to={`/app/channels/${channel.channelId}`} className="text-text hover:text-signal">
                    {channel.name}
                  </Link>
                </td>
                <td className="py-2.5 font-mono text-xs text-text-muted">{channel.channelId}</td>
                <td className="py-2.5 font-mono text-xs text-text-muted">rtk_&hellip;{channel.apiKeySuffix}</td>
                <td className="py-2.5 text-text-muted">{new Date(channel.createdAt).toLocaleDateString()}</td>
                <td className="py-2.5 text-right">
                  <button
                    type="button"
                    onClick={() => deleteMutation.mutate(channel.channelId)}
                    className="text-xs text-text-muted hover:text-error"
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </AppShell>
  )
}
