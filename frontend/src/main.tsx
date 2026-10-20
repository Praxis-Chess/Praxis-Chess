import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
// Bundled fonts (POLISH_PLAN.md D5): latin only, served with the app, so it looks
// the same on every machine and works offline. No CDN request.
import '@fontsource/inter-tight/latin-400.css'
import '@fontsource/inter-tight/latin-500.css'
import '@fontsource/inter-tight/latin-600.css'
import '@fontsource/inter-tight/latin-700.css'
import '@fontsource/jetbrains-mono/latin-400.css'
import '@fontsource/jetbrains-mono/latin-500.css'
import '@fontsource/jetbrains-mono/latin-600.css'
import './index.css'

import { installSampleFetch } from './sample/sampleMode'

// Sample mode (U7) answers /api from its library, so it is in place before the first request.
installSampleFetch().finally(() => {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  )
})
