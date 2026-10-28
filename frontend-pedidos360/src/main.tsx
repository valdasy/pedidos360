import { MsalProvider } from '@azure/msal-react'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import { EntraAuthProvider, PracticeAuthProvider } from './auth/AuthContext'
import { createMsal } from './auth/msal'
import { config } from './config'
import './index.css'

const root = createRoot(document.getElementById('root')!)

if (config.authMode === 'entra') {
  // authConfig -> PublicClientApplication -> MsalProvider -> App (igual que el tutorial)
  createMsal().then((msalInstance) =>
    root.render(
      <StrictMode>
        <MsalProvider instance={msalInstance}>
          <EntraAuthProvider>
            <App />
          </EntraAuthProvider>
        </MsalProvider>
      </StrictMode>,
    ),
  )
} else {
  root.render(
    <StrictMode>
      <PracticeAuthProvider>
        <App />
      </PracticeAuthProvider>
    </StrictMode>,
  )
}
