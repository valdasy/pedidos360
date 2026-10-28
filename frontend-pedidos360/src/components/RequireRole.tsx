import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import type { Role } from '../config'

/**
 * "Guard" de ruta: sin sesión -> /login; sin el rol -> mensaje de acceso denegado.
 * Es solo comodidad visual: quien realmente protege los datos es la API (JWT + roles).
 */
export function RequireRole({ roles, children }: { roles?: Role[]; children: ReactNode }) {
  const { user, hasRole } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  if (roles && !hasRole(...roles)) {
    return (
      <div className="page">
        <div className="alert alert-error">
          Acceso denegado: esta sección requiere el rol {roles.join(' o ')}.
        </div>
      </div>
    )
  }
  return <>{children}</>
}
