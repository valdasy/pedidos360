import { InteractionRequiredAuthError } from '@azure/msal-browser'
import { useMsal } from '@azure/msal-react'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { config, type Role, ROLES } from '../config'
import { loginRequest } from './msal'

export interface User {
  name: string
  email: string
  roles: Role[]
}

interface AuthState {
  mode: typeof config.authMode
  user: User | null
  /** Modo entra: redirige a Microsoft. Modo práctica: recibe el usuario elegido. */
  login: (practiceUser?: User) => void
  logout: () => void
  /** Access token para la API (null en modo práctica). */
  getToken: () => Promise<string | null>
  hasRole: (...roles: Role[]) => boolean
}

const AuthContext = createContext<AuthState | null>(null)

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth debe usarse dentro de un AuthProvider')
  return ctx
}

function withHelpers(base: Omit<AuthState, 'hasRole'>): AuthState {
  return {
    ...base,
    hasRole: (...roles) => !!base.user && roles.some((r) => base.user!.roles.includes(r)),
  }
}

/** Lee el payload de un JWT SOLO para mostrar la interfaz según rol (la API es la que valida). */
function decodeJwt(token: string): Record<string, unknown> {
  const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  const json = decodeURIComponent(
    atob(payload).split('').map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
  )
  return JSON.parse(json)
}

// ---------------------------------------------------------------------------
// Modo "entra": Microsoft Entra ID con MSAL
// ---------------------------------------------------------------------------
export function EntraAuthProvider({ children }: { children: ReactNode }) {
  const { instance, accounts } = useMsal()
  const account = instance.getActiveAccount() ?? accounts[0] ?? null
  const [roles, setRoles] = useState<Role[]>([])

  const getToken = useCallback(async (): Promise<string | null> => {
    if (!account) return null
    try {
      // Silencioso: usa la caché o el refresh token, sin molestar al usuario
      const res = await instance.acquireTokenSilent({ ...loginRequest, account })
      return res.accessToken
    } catch (e) {
      if (e instanceof InteractionRequiredAuthError) {
        await instance.acquireTokenRedirect({ ...loginRequest, account })
      }
      throw e
    }
  }, [instance, account])

  // Los App roles están definidos en el registro de la API, por eso llegan en el
  // access token (claim "roles") y no en el id token.
  useEffect(() => {
    if (!account) return
    getToken()
      .then((t) => {
        const claims = t ? decodeJwt(t) : {}
        const tokenRoles = Array.isArray(claims.roles) ? (claims.roles as string[]) : []
        setRoles(tokenRoles.filter((r): r is Role => ROLES.includes(r as Role)))
      })
      .catch(() => setRoles([]))
  }, [account, getToken])

  const value = useMemo(
    () =>
      withHelpers({
        mode: 'entra',
        user: account ? { name: account.name ?? account.username, email: account.username, roles } : null,
        login: () => void instance.loginRedirect(loginRequest),
        logout: () => void instance.logoutRedirect({ postLogoutRedirectUri: window.location.origin }),
        getToken,
      }),
    [account, roles, instance, getToken],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// ---------------------------------------------------------------------------
// Modo "práctica": sin Azure. Se elige el rol en la pantalla de login.
// El backend debe correr con SECURITY_ENABLED=false.
// ---------------------------------------------------------------------------
const PRACTICE_KEY = 'pedidos360.practiceUser'

export function PracticeAuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => {
    try {
      const saved = sessionStorage.getItem(PRACTICE_KEY)
      return saved ? (JSON.parse(saved) as User) : null
    } catch {
      return null
    }
  })

  const value = useMemo(
    () =>
      withHelpers({
        mode: 'practica',
        user,
        login: (u?: User) => {
          if (!u) return
          try {
            sessionStorage.setItem(PRACTICE_KEY, JSON.stringify(u))
          } catch {
            /* sin almacenamiento: la sesión dura hasta recargar */
          }
          setUser(u)
        },
        logout: () => {
          try {
            sessionStorage.removeItem(PRACTICE_KEY)
          } catch {
            /* ignorar */
          }
          setUser(null)
        },
        getToken: async () => null,
      }),
    [user],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
