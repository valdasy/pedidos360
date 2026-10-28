import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import type { Role } from '../config'

const MENU: { to: string; label: string; roles: Role[] }[] = [
  { to: '/dashboard', label: 'Inicio', roles: ['Admin', 'Operador', 'Cliente'] },
  { to: '/orders', label: 'Pedidos', roles: ['Admin', 'Operador', 'Cliente'] },
  { to: '/catalog', label: 'Catálogo', roles: ['Admin', 'Operador'] },
  { to: '/admin/mq', label: 'Colas RabbitMQ', roles: ['Admin'] },
]

/** Layout base: menú lateral filtrado por rol + cabecera con el usuario. */
export function Layout() {
  const { user, logout, hasRole, mode } = useAuth()

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="brand">
          Pedidos<span>360</span>
        </div>
        <nav>
          {MENU.filter((m) => hasRole(...m.roles)).map((m) => (
            <NavLink key={m.to} to={m.to} className={({ isActive }) => (isActive ? 'active' : '')}>
              {m.label}
            </NavLink>
          ))}
        </nav>
        {mode === 'practica' && <div className="mode-tag">Modo práctica (sin Entra ID)</div>}
      </aside>

      <div className="main">
        <header className="topbar">
          <div>
            <strong>{user?.name}</strong>
            <span className="muted"> · {user?.email}</span>
          </div>
          <div className="topbar-right">
            {user?.roles.length ? (
              user.roles.map((r) => (
                <span key={r} className="role-chip">
                  {r}
                </span>
              ))
            ) : (
              <span className="role-chip role-none">sin rol asignado</span>
            )}
            <button className="btn btn-ghost" onClick={logout}>
              Cerrar sesión
            </button>
          </div>
        </header>
        <main>
          <Outlet />
        </main>
      </div>
    </div>
  )
}
