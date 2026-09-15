import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthContext'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { LoginPage } from './pages/LoginPage'
import { SignupPage } from './pages/SignupPage'
import { ChannelListPage } from './pages/ChannelListPage'
import { ChannelDetailPage } from './pages/ChannelDetailPage'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/" element={<Navigate to="/app/channels" replace />} />
          <Route path="/app" element={<Navigate to="/app/channels" replace />} />
          <Route path="/app/login" element={<LoginPage />} />
          <Route path="/app/signup" element={<SignupPage />} />
          <Route
            path="/app/channels"
            element={
              <ProtectedRoute>
                <ChannelListPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/app/channels/:channelId"
            element={
              <ProtectedRoute>
                <ChannelDetailPage />
              </ProtectedRoute>
            }
          />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
