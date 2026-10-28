import type { ReactNode } from 'react'
import type { OrderStatus } from '../api/types'

export const STATUS_LABEL: Record<OrderStatus, string> = {
  CREADO: 'Creado',
  ACEPTADO: 'Aceptado',
  EN_PREPARACION: 'En preparación',
  DESPACHADO: 'Despachado',
  ENTREGADO: 'Entregado',
  CANCELADO: 'Cancelado',
}

/** OrderStatusBadgeComponent del caso: el estado con un color. */
export function StatusBadge({ status }: { status: OrderStatus }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>
}

export const clp = (n: number) =>
  n.toLocaleString('es-CL', { style: 'currency', currency: 'CLP', maximumFractionDigits: 0 })

export const fecha = (iso: string | null) =>
  iso ? new Date(iso).toLocaleString('es-CL', { dateStyle: 'short', timeStyle: 'short' }) : '—'

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null
  const msg = error instanceof Error ? error.message : String(error)
  return <div className="alert alert-error">{msg}</div>
}

export function Card({ title, children, actions }: { title?: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card-header">
          {title && <h2>{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  )
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {hint && <span className="stat-hint">{hint}</span>}
    </div>
  )
}
