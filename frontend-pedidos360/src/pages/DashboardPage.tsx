import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import type { Alert, Order, OrderStatus } from '../api/types'
import { useApi } from '../api/useApi'
import { useAuth } from '../auth/AuthContext'
import { Card, clp, ErrorBox, fecha, Stat, STATUS_LABEL, StatusBadge } from '../components/ui'

const EN_CURSO: OrderStatus[] = ['ACEPTADO', 'EN_PREPARACION', 'DESPACHADO']

/** Ruta /dashboard: resumen distinto según el rol. */
export function DashboardPage() {
  const api = useApi()
  const { user, hasRole } = useAuth()
  const [orders, setOrders] = useState<Order[]>([])
  const [alerts, setAlerts] = useState<Alert[] | null>(null)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    api.orders.list().then(setOrders).catch(setError)
    if (hasRole('Admin')) api.mq.alerts().then(setAlerts).catch(() => setAlerts(null))
  }, [api, hasRole])

  const count = (s: OrderStatus) => orders.filter((o) => o.estado === s).length
  const entregados = orders.filter((o) => o.estado === 'ENTREGADO')
  const ventas = entregados.reduce((sum, o) => sum + o.total, 0)
  const leadTimes = entregados
    .filter((o) => o.entregadoEn)
    .map((o) => (new Date(o.entregadoEn!).getTime() - new Date(o.creadoEn).getTime()) / 60000)
  const leadTime = leadTimes.length ? leadTimes.reduce((a, b) => a + b, 0) / leadTimes.length : null

  return (
    <div className="page">
      <h1>Hola, {user?.name?.split(' ')[0]}</h1>
      <ErrorBox error={error} />

      {hasRole('Admin') && (
        <div className="stats">
          <Stat label="Pedidos totales" value={orders.length} />
          <Stat label="Ventas entregadas" value={clp(ventas)} hint={`${entregados.length} pedidos`} />
          <Stat
            label="Lead time promedio"
            value={leadTime === null ? '—' : `${leadTime.toFixed(1)} min`}
            hint="de creado a entregado"
          />
          <Stat
            label="Alertas de colas"
            value={alerts === null ? '—' : alerts.length}
            hint={alerts?.some((a) => a.level === 'CRITICAL') ? 'hay alertas críticas' : 'RabbitMQ'}
          />
        </div>
      )}

      {hasRole('Admin', 'Operador') && (
        <>
          <div className="stats">
            <Stat label="Pendientes de aceptar" value={count('CREADO')} />
            {EN_CURSO.map((s) => (
              <Stat key={s} label={STATUS_LABEL[s]} value={count(s)} />
            ))}
          </div>
          <Card title="Pendientes de aceptar" actions={<Link to="/orders">Ver todos</Link>}>
            <OrderTable orders={orders.filter((o) => o.estado === 'CREADO').slice(0, 5)} />
          </Card>
        </>
      )}

      {hasRole('Cliente') && !hasRole('Admin', 'Operador') && (
        <Card title="Tus últimos pedidos" actions={<Link to="/orders">Hacer un pedido</Link>}>
          <OrderTable orders={orders.slice(0, 5)} />
        </Card>
      )}

      {alerts && alerts.length > 0 && (
        <Card title="Alertas RabbitMQ" actions={<Link to="/admin/mq">Administrar colas</Link>}>
          <ul className="alerts">
            {alerts.map((a, i) => (
              <li key={i} className={`alert-${a.level.toLowerCase()}`}>
                <strong>{a.level}</strong> {a.queue}: {a.message}
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  )
}

function OrderTable({ orders }: { orders: Order[] }) {
  if (!orders.length) return <p className="muted">No hay pedidos.</p>
  return (
    <table>
      <thead>
        <tr>
          <th>#</th>
          <th>Cliente</th>
          <th>Estado</th>
          <th className="num">Total</th>
          <th>Creado</th>
        </tr>
      </thead>
      <tbody>
        {orders.map((o) => (
          <tr key={o.id}>
            <td>
              <Link to={`/orders?id=${o.id}`}>{o.id}</Link>
            </td>
            <td>{o.clienteNombre}</td>
            <td>
              <StatusBadge status={o.estado} />
            </td>
            <td className="num">{clp(o.total)}</td>
            <td>{fecha(o.creadoEn)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
