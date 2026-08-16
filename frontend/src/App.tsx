import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Layout } from './components/Layout'
import { Today } from './pages/Today'
import { Session } from './pages/Session'
import { Progress } from './pages/Progress'
import { Library } from './pages/Library'
import { Dashboard } from './pages/Dashboard'
import { GameList } from './pages/GameList'
import { GameAnalysis } from './pages/GameAnalysis'
import { PatternReport } from './pages/PatternReport'
import { TrainingPlan } from './pages/TrainingPlan'
import { Insights } from './pages/Insights'
import { Drills } from './pages/Drills'
import { PraxHost } from './prax/PraxHost'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          {/* Session is full-screen — no layout nav */}
          <Route path="session/:id" element={<Session />} />

          <Route element={<Layout />}>
            {/* New primary navigation (Phase 2) */}
            <Route index element={<Today />} />
            <Route path="progress" element={<Progress />} />
            <Route path="library" element={<Library />} />

            {/* Legacy routes — still accessible, not in primary nav */}
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="games" element={<GameList />} />
            <Route path="games/:id" element={<GameAnalysis />} />
            <Route path="insights" element={<Insights />} />
            <Route path="drills" element={<Drills />} />
            <Route path="patterns" element={<PatternReport />} />
            <Route path="training" element={<TrainingPlan />} />

            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>

        {/* Mounted once, never unmounted — particle identity survives navigation. */}
        <PraxHost />
      </BrowserRouter>
    </QueryClientProvider>
  )
}
