import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ApiError } from '../api/client'

export function SignupPage() {
  const { signup } = useAuth()
  const navigate = useNavigate()
  const [tenantName, setTenantName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setIsSubmitting(true)
    try {
      await signup(tenantName, email, password)
      navigate('/app/channels')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Something went wrong. Try again.')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex items-center gap-2">
          <span className="h-2.5 w-2.5 rounded-full bg-signal animate-pulse-signal" />
          <span className="font-mono text-sm tracking-wide text-text-muted">realtime</span>
        </div>

        <h1 className="mb-6 text-xl font-medium text-text">Create an account</h1>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="tenantName" className="mb-1.5 block text-sm text-text-muted">
              Team name
            </label>
            <input
              id="tenantName"
              type="text"
              required
              autoFocus
              value={tenantName}
              onChange={(event) => setTenantName(event.target.value)}
              className="w-full rounded-[3px] border border-border bg-surface px-3 py-2 text-sm text-text outline-none focus:border-signal"
            />
          </div>
          <div>
            <label htmlFor="email" className="mb-1.5 block text-sm text-text-muted">
              Email
            </label>
            <input
              id="email"
              type="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              className="w-full rounded-[3px] border border-border bg-surface px-3 py-2 text-sm text-text outline-none focus:border-signal"
            />
          </div>
          <div>
            <label htmlFor="password" className="mb-1.5 block text-sm text-text-muted">
              Password
            </label>
            <input
              id="password"
              type="password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className="w-full rounded-[3px] border border-border bg-surface px-3 py-2 text-sm text-text outline-none focus:border-signal"
            />
          </div>

          {error && (
            <p className="text-sm text-error" role="alert">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={isSubmitting}
            className="w-full rounded-[3px] bg-signal px-3 py-2 text-sm font-medium text-ink transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            {isSubmitting ? 'Creating account…' : 'Create account'}
          </button>
        </form>

        <p className="mt-6 text-sm text-text-muted">
          Already have an account?{' '}
          <Link to="/app/login" className="text-signal hover:underline">
            Log in
          </Link>
        </p>
      </div>
    </div>
  )
}
