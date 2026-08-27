import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { Toaster } from '@/components/ui/sonner'
import Layout from './components/layout/Layout'
import LoginPage from './pages/LoginPage'
import TransactionsPage from './pages/TransactionsPage'
import UploadPage from './pages/UploadPage'
import StatsPage from './pages/StatsPage'
import ManualEntryPage from './pages/ManualEntryPage'
import CalendarPage from './pages/CalendarPage'
import BalancesPage from './pages/BalancesPage'
import GoalsPage from './pages/GoalsPage'
import BreakdownPage from './pages/BreakdownPage'

function App() {
  return (
    <>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<Layout />}>
            <Route path="/" element={<TransactionsPage />} />
            <Route path="/upload" element={<UploadPage />} />
            <Route path="/stats" element={<StatsPage />} />
            <Route path="/breakdown" element={<BreakdownPage />} />
            <Route path="/calendar" element={<CalendarPage />} />
            <Route path="/balances" element={<BalancesPage />} />
            <Route path="/goals" element={<GoalsPage />} />
            <Route path="/add" element={<ManualEntryPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
      <Toaster />
    </>
  )
}

export default App
