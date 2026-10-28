/** Configuración leída de .env.local (variables VITE_*). */
const env = import.meta.env

export type AuthMode = 'entra' | 'practica'

export const config = {
  authMode: (env.VITE_AUTH_MODE === 'entra' ? 'entra' : 'practica') as AuthMode,
  entra: {
    clientId: env.VITE_ENTRA_CLIENT_ID ?? '',
    tenantId: env.VITE_ENTRA_TENANT_ID ?? '',
    apiScope: env.VITE_API_SCOPE ?? '',
  },
  apiBaseUrl: (env.VITE_API_BASE_URL ?? '').replace(/\/$/, ''),
}

export type Role = 'Admin' | 'Operador' | 'Cliente'
export const ROLES: Role[] = ['Admin', 'Operador', 'Cliente']
