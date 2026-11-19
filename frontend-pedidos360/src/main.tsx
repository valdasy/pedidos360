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
  createMsal()
    .then((msalInstance) =>
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
    .catch((e: unknown) => {
      // Sin esto, un error al volver de Microsoft (p. ej. AADSTS...) deja la página en blanco
      console.error('Error de inicio de sesión (MSAL):', e)
      const msg = e instanceof Error ? `${e.name}: ${e.message}` : String(e)
      root.render(
        <div style={{ maxWidth: 720, margin: '48px auto', padding: 16, fontFamily: 'system-ui' }}>
          <h2>No se pudo completar el inicio de sesión</h2>
          <pre style={{ whiteSpace: 'pre-wrap', background: '#f4f4f5', padding: 12 }}>{msg}</pre>
          <button onClick={() => { sessionStorage.clear(); window.location.href = '/' }}>
            Limpiar sesión y volver a intentar
          </button>
        </div>,
      )
    })
} else {
  root.render(
    <StrictMode>
      <PracticeAuthProvider>
        <App />
      </PracticeAuthProvider>
    </StrictMode>,
  )
}
