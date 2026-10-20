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
import { GameReview } from './pages/GameReview'
import { Insights } from './pages/Insights'
import { PlayImprove } from './pages/PlayImprove'
import { AskPrax } from './pages/AskPrax'
import { Drills } from './pages/Drills'
import { Settings } from './pages/Settings'
import EvidenceLab from './pages/EvidenceLab'
import RuleValidation from './pages/RuleValidation'
import { PraxHost } from './prax/PraxHost'
import { AmbientBackground } from './background/AmbientBackground'
import { Page, type PageWidth } from './components/Page'
import { RailFixture } from './pages/RailFixture'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})

/** A route's page, centred on its width (POLISH_PLAN.md D1). */
const at = (width: PageWidth, element: React.ReactNode) => <Page width={width}>{element}</Page>

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          {/* Session is full-screen — no layout nav */}
          <Route path="session/:id" element={<Session />} />

          <Route element={<Layout />}>
            {/* New primary navigation (Phase 2) */}
            {/* Today declares its own frame: the standard width, with its rail (U3). */}
            <Route index element={<Today />} />
            <Route path="progress" element={at('standard', <Progress />)} />
            <Route path="library" element={at('standard', <Library />)} />
            {/* Play declares its own width: standard before a game, wide during one. */}
            <Route path="play" element={<PlayImprove />} />
            <Route path="play/review/:id" element={at('wide', <GameReview />)} />
            <Route path="ask" element={at('reading', <AskPrax />)} />

            {/* Legacy routes — still accessible, not in primary nav */}
            <Route path="dashboard" element={at('standard', <Dashboard />)} />
            <Route path="games" element={at('standard', <GameList />)} />
            <Route path="games/:id" element={at('wide', <GameAnalysis />)} />
            {/* Phase 2 lab: re-explains a game from a backend-rendered
                evidence block. Read-only, and reachable by URL rather than
                from the nav — it is a measurement surface, not a feature. */}
            <Route path="evidence/:id" element={at('standard', <EvidenceLab />)} />
            {/* Phase 3: hand labels that validate the rules. Measurement, not a feature. */}
            <Route path="labels" element={at('standard', <RuleValidation />)} />
            <Route path="insights" element={at('standard', <Insights />)} />
            <Route path="insights/:tab" element={at('standard', <Insights />)} />
            <Route path="settings" element={at('standard', <Settings />)} />
            <Route path="drills" element={at('standard', <Drills />)} />
            {/* Dev only: the rail slot, until a page adopts it (U3). */}
            {import.meta.env.DEV && <Route path="dev/rail" element={<RailFixture />} />}
            {/* Phase 12: the Pattern Report and Training Plan are tabs inside Insights. */}
            <Route path="patterns" element={<Navigate to="/insights/patterns" replace />} />
            <Route path="training" element={<Navigate to="/insights/training" replace />} />

            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>

        {/* Mounted once, never unmounted — particle identity survives navigation. */}
        {/* Behind every page; reads Prax's state to set how lively it is. */}
        <AmbientBackground />
        <PraxHost />
      </BrowserRouter>
    </QueryClientProvider>
  )
}
