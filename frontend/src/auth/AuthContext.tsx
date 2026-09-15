import { createContext, useContext, useState, type ReactNode } from 'react'
import { api, clearToken, getToken, setToken } from '../api/client'

interface AuthContextValue {
  isAuthenticated: boolean
  login(email: string, password: string): Promise<void>
  signup(tenantName: string, email: string, password: string): Promise<void>
  logout(): void
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

/** Deliberately doesn't navigate on its own — login/signup pages await these calls
 * and navigate themselves. Keeps this context free of a router dependency and easy
 * to reason about in isolation. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(() => getToken() !== null)

  async function login(email: string, password: string) {
    const { token } = await api.login(email, password)
    setToken(token)
    setIsAuthenticated(true)
  }

  async function signup(tenantName: string, email: string, password: string) {
    const { token } = await api.signup(tenantName, email, password)
    setToken(token)
    setIsAuthenticated(true)
  }

  function logout() {
    clearToken()
    setIsAuthenticated(false)
  }

  return (
    <AuthContext.Provider value={{ isAuthenticated, login, signup, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return ctx
}
