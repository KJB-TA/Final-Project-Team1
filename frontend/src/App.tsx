import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './context/AuthProvider'
import { ToastProvider } from './components/ToastProvider'
import GNB from './components/GNB'
import ScrollToTop from './components/ScrollToTop'
import HomePage from './pages/HomePage'
import AuthPage from './pages/AuthPage'
import AdminPage from './pages/AdminPage'
import ExpoDetailPage from './pages/ExpoDetailPage'
import ChannelCreatePage from './pages/ChannelCreatePage'
import ExpoManagePage from './pages/ExpoManagePage'
import RoundManagePage from './pages/RoundManagePage'
import HostChannelPage from './pages/HostChannelPage'
import MyReservationsPage from './pages/MyReservationsPage'
import CheckinPage from './pages/CheckinPage'
import MyPage from './pages/MyPage'
import RecommendationsPage from './pages/RecommendationsPage'
import CalendarPage from './pages/CalendarPage'
import NaverCallback from './pages/NaverCallback'
import KakaoCallback from './pages/KakaoCallback'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <ToastProvider>
          <GNB />
          <ScrollToTop />
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/auth" element={<AuthPage />} />
            <Route path="/auth/naver/callback" element={<NaverCallback />} />
            <Route path="/auth/kakao/callback" element={<KakaoCallback />} />
            <Route path="/expos" element={<HomePage />} />
            <Route path="/expos/:expoId" element={<ExpoDetailPage />} />
            <Route path="/calendar" element={<CalendarPage />} />
            <Route path="/admin" element={<AdminPage />} />
            <Route path="/host/channel" element={<HostChannelPage />} />
            <Route path="/host/channel/new" element={<ChannelCreatePage />} />
            <Route path="/host/expos/new" element={<ExpoManagePage />} />
            <Route path="/host/expos/:expoId/edit" element={<ExpoManagePage />} />
            <Route path="/host/expos/:expoId/rounds" element={<RoundManagePage />} />
            <Route path="/my/reservations" element={<MyReservationsPage />} />
            <Route path="/my/profile" element={<MyPage />} />
            <Route path="/my/recommendations" element={<RecommendationsPage />} />
            <Route path="/host/checkin" element={<CheckinPage />} />
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </BrowserRouter>
  )
}
