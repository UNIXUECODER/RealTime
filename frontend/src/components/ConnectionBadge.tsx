import type { ConnectionStatus } from '../hooks/useChannelSocket'

const STATUS_CONFIG: Record<ConnectionStatus, { label: string; color: string; pulse?: boolean }> = {
  connecting: { label: 'Connecting', color: 'bg-idle' },
  open: { label: 'Connected', color: 'bg-success', pulse: true },
  closed: { label: 'Disconnected', color: 'bg-idle' },
  error: { label: 'Connection error', color: 'bg-error' },
  unauthorized: { label: 'Session expired — log in again', color: 'bg-error' },
}

export function ConnectionBadge({ status }: { status: ConnectionStatus }) {
  const config = STATUS_CONFIG[status]
  return (
    <div className="flex items-center gap-2 text-sm text-text-muted">
      <span className={`h-2 w-2 rounded-full ${config.color} ${config.pulse ? 'animate-pulse-signal' : ''}`} />
      {config.label}
    </div>
  )
}
