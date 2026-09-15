import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

/** Shared chrome for every authenticated page. Logging out just flips
 * isAuthenticated to false — ProtectedRoute (already wrapping every page that uses
 * this shell) reacts to that and redirects, so there's no navigate() call needed here. */
export function AppShell({ children }: { children: ReactNode }) {
  const { logout } = useAuth()
  return (
    <div className="min-h-screen">
      <header className="flex items-center justify-between border-b border-border px-6 py-3">
        <Link to="/app/channels" className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-full bg-signal animate-pulse-signal" />
          <span className="font-mono text-sm tracking-wide text-text-muted">realtime</span>
        </Link>
        <button type="button" onClick={logout} className="text-sm text-text-muted hover:text-text">
          Log out
        </button>
      </header>
      <main className="mx-auto max-w-4xl px-6 py-8">{children}</main>
    </div>
  )
}
