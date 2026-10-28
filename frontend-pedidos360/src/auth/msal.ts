import { PublicClientApplication, type Configuration } from '@azure/msal-browser'
import { config } from '../config'

/** Igual que authConfig.ts del tutorial, pero con valores desde .env.local. */
export const msalConfig: Configuration = {
  auth: {
    clientId: config.entra.clientId,
    authority: `https://login.microsoftonline.com/${config.entra.tenantId}`,
    redirectUri: window.location.origin,          // http://localhost:5173 en local
    postLogoutRedirectUri: window.location.origin,
  },
  cache: {
    cacheLocation: 'sessionStorage',
  },
}

/**
 * Scopes que se piden al iniciar sesión. El scope de NUESTRA API hace que Entra
 * entregue un access token cuyo "aud" es la API y que incluye el claim "roles".
 */
export const loginRequest = {
  scopes: [config.entra.apiScope],
}

/** Crea e inicializa MSAL (obligatorio en msal-browser v3+ antes de usarlo). */
export async function createMsal(): Promise<PublicClientApplication> {
  const instance = new PublicClientApplication(msalConfig)   // como en el tutorial
  await instance.initialize()
  // Procesa la respuesta cuando Entra devuelve al usuario al Redirect URI
  const result = await instance.handleRedirectPromise()
  if (result?.account) {
    instance.setActiveAccount(result.account)
  } else if (!instance.getActiveAccount() && instance.getAllAccounts().length > 0) {
    instance.setActiveAccount(instance.getAllAccounts()[0])
  }
  return instance
}
