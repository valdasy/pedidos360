import { config } from '../config'

/** Error de la API con el detalle Problem Details (RFC 9457) que devuelven los microservicios. */
export class ApiError extends Error {
  readonly status: number
  readonly body: Record<string, unknown> | null

  constructor(status: number, body: Record<string, unknown> | null) {
    super(
      (body?.detail as string) ??
        (status === 401
          ? 'No autenticado: inicia sesión nuevamente'
          : status === 403
            ? 'No tienes permiso para esta acción'
            : `Error HTTP ${status}`),
    )
    this.status = status
    this.body = body
  }
}

type TokenProvider = () => Promise<string | null>

/**
 * fetch con el token de Entra: Authorization: Bearer <access token>.
 * API Gateway lo valida primero (JWT authorizer) y luego cada microservicio otra vez.
 */
export async function apiFetch<T>(getToken: TokenProvider, path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = await getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const res = await fetch(config.apiBaseUrl + path, { ...init, headers })
  if (!res.ok) {
    let body: Record<string, unknown> | null = null
    try {
      body = await res.json()
    } catch {
      /* respuesta sin JSON (p. ej. 401 de API Gateway) */
    }
    throw new ApiError(res.status, body)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}
