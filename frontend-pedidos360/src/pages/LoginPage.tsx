import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { type Role, ROLES } from '../config'

const PRACTICE_USERS: Record<Role, { name: string; email: string }> = {
  Admin: { name: 'Admin Pedidos360', email: 'admin@pedidos360.cl' },
  Operador: { name: 'Olga Operadora', email: 'operador@pedidos360.cl' },
  Cliente: { name: 'Ana Cliente', email: 'ana@ejemplo.cl' },
}

/** Ruta /login (pública). */
export function LoginPage() {
  const { user, login, mode } = useAuth()
  const [role, setRole] = useState<Role>('Cliente')

  if (user) return <Navigate to="/dashboard" replace />

  return (
    <div className="login">
      <div className="login-card">
        <div className="brand brand-lg">
          Pedidos<span>360</span>
        </div>
        <p className="muted">Gestión de pedidos · Desarrollo Cloud Native</p>

        {mode === 'entra' ? (
          <>
            <button className="btn btn-primary btn-block" onClick={() => login()}>
              Iniciar sesión con Microsoft
            </button>
            <p className="hint">
              Serás redirigido a Microsoft Entra ID. Tu rol (Admin, Operador o Cliente) viene en el token.
            </p>
          </>
        ) : (
          <>
            <div className="alert alert-info">
              <strong>Modo práctica:</strong> sin Microsoft Entra ID. Elige un rol para simular el login. Los
              microservicios deben correr con <code>SECURITY_ENABLED=false</code>.
            </div>
            <div className="role-picker">
              {ROLES.map((r) => (
                <label key={r} className={role === r ? 'selected' : ''}>
                  <input type="radio" name="role" checked={role === r} onChange={() => setRole(r)} />
                  <strong>{r}</strong>
                  <span>{PRACTICE_USERS[r].email}</span>
                </label>
              ))}
            </div>
            <button
              className="btn btn-primary btn-block"
              onClick={() => login({ ...PRACTICE_USERS[role], roles: [role] })}
            >
              Entrar como {role}
            </button>
          </>
        )}
      </div>
    </div>
  )
}
