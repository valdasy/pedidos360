import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { Order, OrderStatus, Product, StatusChange } from '../api/types'
import { ORDER_STATUSES } from '../api/types'
import { useApi } from '../api/useApi'
import { useAuth } from '../auth/AuthContext'
import { Card, clp, ErrorBox, fecha, STATUS_LABEL, StatusBadge } from '../components/ui'

/** Ruta /orders: OrderListComponent + OrderDetailComponent + formulario de creación. */
export function OrdersPage() {
  const api = useApi()
  const { hasRole } = useAuth()
  const [params, setParams] = useSearchParams()
  const [orders, setOrders] = useState<Order[]>([])
  const [filtro, setFiltro] = useState<OrderStatus | ''>('')
  const [error, setError] = useState<unknown>(null)
  const [creating, setCreating] = useState(false)
  const selectedId = Number(params.get('id')) || null

  const load = useCallback(() => {
    setError(null)
    api.orders.list(filtro || undefined).then(setOrders).catch(setError)
  }, [api, filtro])

  useEffect(load, [load])

  const select = (id: number | null) => setParams(id ? { id: String(id) } : {})
  const puedeCrear = hasRole('Cliente', 'Operador')

  return (
    <div className="page">
      <div className="page-header">
        <h1>Pedidos</h1>
        {puedeCrear && (
          <button className="btn btn-primary" onClick={() => setCreating(true)}>
            Nuevo pedido
          </button>
        )}
      </div>
      <ErrorBox error={error} />

      {creating && (
        <CreateOrder
          onDone={(o) => {
            setCreating(false)
            load()
            if (o) select(o.id)
          }}
        />
      )}

      <div className="split">
        <Card
          title="Listado"
          actions={
            <select value={filtro} onChange={(e) => setFiltro(e.target.value as OrderStatus | '')}>
              <option value="">Todos los estados</option>
              {ORDER_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {STATUS_LABEL[s]}
                </option>
              ))}
            </select>
          }
        >
          {orders.length === 0 ? (
            <p className="muted">No hay pedidos.</p>
          ) : (
            <table className="selectable">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Cliente</th>
                  <th>Estado</th>
                  <th className="num">Total</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((o) => (
                  <tr key={o.id} className={o.id === selectedId ? 'selected' : ''} onClick={() => select(o.id)}>
                    <td>{o.id}</td>
                    <td>
                      {o.clienteNombre}
                      <div className="muted small">{o.clienteEmail}</div>
                    </td>
                    <td>
                      <StatusBadge status={o.estado} />
                    </td>
                    <td className="num">{clp(o.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        {selectedId && <OrderDetail id={selectedId} onChanged={load} onDeleted={() => (select(null), load())} />}
      </div>
    </div>
  )
}

function OrderDetail({ id, onChanged, onDeleted }: { id: number; onChanged: () => void; onDeleted: () => void }) {
  const api = useApi()
  const { hasRole } = useAuth()
  const [order, setOrder] = useState<Order | null>(null)
  const [history, setHistory] = useState<StatusChange[]>([])
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    setError(null)
    api.orders.get(id).then(setOrder).catch(setError)
    api.orders.history(id).then(setHistory).catch(() => setHistory([]))
  }, [api, id])

  useEffect(load, [load])

  const soloCliente = hasRole('Cliente') && !hasRole('Admin', 'Operador')
  // El Cliente solo puede cancelar mientras el pedido está CREADO (la API lo exige igual)
  const acciones =
    order?.siguientesEstados.filter((s) => !soloCliente || (s === 'CANCELADO' && order.estado === 'CREADO')) ?? []

  const cambiar = async (estado: OrderStatus) => {
    setBusy(true)
    setError(null)
    try {
      setOrder(await api.orders.changeStatus(id, estado))
      api.orders.history(id).then(setHistory)
      onChanged()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  const eliminar = async () => {
    if (!confirm(`¿Eliminar el pedido #${id}?`)) return
    try {
      await api.orders.remove(id)
      onDeleted()
    } catch (e) {
      setError(e)
    }
  }

  if (!order) return <Card title={`Pedido #${id}`}>{error ? <ErrorBox error={error} /> : <p>Cargando…</p>}</Card>

  return (
    <Card title={`Pedido #${order.id}`} actions={<StatusBadge status={order.estado} />}>
      <ErrorBox error={error} />
      <p>
        <strong>{order.clienteNombre}</strong> · {order.clienteEmail}
      </p>
      <table>
        <thead>
          <tr>
            <th>Producto</th>
            <th className="num">Cant.</th>
            <th className="num">Precio</th>
            <th className="num">Subtotal</th>
          </tr>
        </thead>
        <tbody>
          {order.items.map((i) => (
            <tr key={i.productoId}>
              <td>{i.nombre}</td>
              <td className="num">{i.cantidad}</td>
              <td className="num">{clp(i.precioUnitario)}</td>
              <td className="num">{clp(i.subtotal)}</td>
            </tr>
          ))}
          <tr className="total-row">
            <td colSpan={3}>Total</td>
            <td className="num">{clp(order.total)}</td>
          </tr>
        </tbody>
      </table>

      {acciones.length > 0 && (
        <div className="actions">
          {acciones.map((s) => (
            <button
              key={s}
              disabled={busy}
              className={`btn ${s === 'CANCELADO' ? 'btn-danger' : 'btn-primary'}`}
              onClick={() => cambiar(s)}
            >
              {s === 'CANCELADO' ? 'Cancelar pedido' : `Pasar a ${STATUS_LABEL[s]}`}
            </button>
          ))}
          {order.estado === 'CREADO' && (
            <button className="btn btn-ghost" onClick={eliminar} disabled={busy}>
              Eliminar
            </button>
          )}
        </div>
      )}
      {order.estado === 'CREADO' && hasRole('Admin', 'Operador') && (
        <p className="hint">Al aceptar se descuenta el stock en ms-catalog y se envía el ticket a cocina (RabbitMQ).</p>
      )}

      <h3>Historial</h3>
      <ol className="timeline">
        {history.map((h, i) => (
          <li key={i}>
            <StatusBadge status={h.estadoNuevo} /> <span className="muted">{fecha(h.cambiadoEn)}</span>
          </li>
        ))}
      </ol>
    </Card>
  )
}

function CreateOrder({ onDone }: { onDone: (o: Order | null) => void }) {
  const api = useApi()
  const { user, hasRole } = useAuth()
  const soloCliente = hasRole('Cliente') && !hasRole('Admin', 'Operador')
  const [products, setProducts] = useState<Product[]>([])
  const [qty, setQty] = useState<Record<number, number>>({})
  const [nombre, setNombre] = useState(soloCliente ? (user?.name ?? '') : '')
  const [email, setEmail] = useState(soloCliente ? (user?.email ?? '') : '')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.catalog.list().then(setProducts).catch(setError)
  }, [api])

  const items = Object.entries(qty)
    .filter(([, c]) => c > 0)
    .map(([id, cantidad]) => ({ productoId: Number(id), cantidad }))
  const total = items.reduce((s, i) => s + (products.find((p) => p.id === i.productoId)?.precio ?? 0) * i.cantidad, 0)

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      onDone(await api.orders.create({ clienteNombre: nombre, clienteEmail: email, items }))
    } catch (e) {
      setError(e)
      setBusy(false)
    }
  }

  return (
    <Card title="Nuevo pedido">
      <ErrorBox error={error} />
      <div className="form-row">
        <label>
          Nombre del cliente
          <input value={nombre} onChange={(e) => setNombre(e.target.value)} />
        </label>
        <label>
          Email del cliente
          <input value={email} onChange={(e) => setEmail(e.target.value)} disabled={soloCliente} />
        </label>
      </div>
      <table>
        <thead>
          <tr>
            <th>Producto</th>
            <th className="num">Precio</th>
            <th className="num">Stock</th>
            <th className="num">Cantidad</th>
          </tr>
        </thead>
        <tbody>
          {products.map((p) => (
            <tr key={p.id}>
              <td>{p.nombre}</td>
              <td className="num">{clp(p.precio)}</td>
              <td className="num">{p.stock}</td>
              <td className="num">
                <input
                  type="number"
                  min={0}
                  className="qty"
                  value={qty[p.id] ?? 0}
                  onChange={(e) => setQty({ ...qty, [p.id]: Math.max(0, Number(e.target.value)) })}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="actions">
        <span className="total">Total: {clp(total)}</span>
        <button className="btn btn-ghost" onClick={() => onDone(null)}>
          Cancelar
        </button>
        <button className="btn btn-primary" disabled={busy || !items.length || !nombre || !email} onClick={submit}>
          Crear pedido
        </button>
      </div>
      <p className="hint">El stock se descuenta recién cuando un operador acepta el pedido.</p>
    </Card>
  )
}
