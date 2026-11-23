import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { AuditEvent, AuditResumen } from '../api/types'
import { useApi } from '../api/useApi'
import { Card, ErrorBox, fecha } from '../components/ui'

const TIPOS = [
  'PEDIDO_CREADO', 'PEDIDO_ESTADO_CAMBIADO', 'PEDIDO_CANCELADO',
  'STOCK_RESERVADO', 'STOCK_LIBERADO', 'PRODUCTO_CREADO', 'PRODUCTO_ACTUALIZADO', 'PRODUCTO_ELIMINADO',
]

/**
 * Ruta /audit (Admin): consume ms-audit.
 * Muestra la trazabilidad guardada desde los tópicos "pedidos.eventos" e "inventario.eventos",
 * con el tópico, la partición y el offset de cada evento en Kafka.
 */
export function AuditPage() {
  const api = useApi()
  const [resumen, setResumen] = useState<AuditResumen | null>(null)
  const [eventos, setEventos] = useState<AuditEvent[]>([])
  const [pedidoId, setPedidoId] = useState('')
  const [tipo, setTipo] = useState('')
  const [entidad, setEntidad] = useState('')
  const [modo, setModo] = useState<'busqueda' | 'timeline'>('busqueda')
  const [error, setError] = useState<unknown>(null)

  const buscar = useCallback(() => {
    setError(null)
    const id = Number(pedidoId)
    const consulta = pedidoId && id > 0 ? api.audit.timeline(id) : api.audit.buscar({ tipo, entidad, limit: 100 })
    setModo(pedidoId ? 'timeline' : 'busqueda')
    Promise.all([consulta, api.audit.resumen()])
      .then(([e, r]) => {
        setEventos(e)
        setResumen(r)
      })
      .catch(setError)
  }, [api, pedidoId, tipo, entidad])

  useEffect(() => {
    buscar()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api])

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    buscar()
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Auditoría</h1>
        <span className="muted small">
          Fuente: Kafka <code>pedidos.eventos</code> + <code>inventario.eventos</code> · grupo{' '}
          <code>pedidos360-audit</code>
        </span>
      </div>
      <ErrorBox error={error} />

      {resumen && (
        <div className="chips">
          <span className="chip chip-strong">{resumen.totalEventos} eventos</span>
          {Object.entries(resumen.porTipo).map(([t, n]) => (
            <span key={t} className="chip">
              {t} <strong>{n}</strong>
            </span>
          ))}
        </div>
      )}

      <Card>
        <form className="form-row" onSubmit={onSubmit}>
          <label>
            Pedido #
            <input type="number" min={1} value={pedidoId} onChange={(e) => setPedidoId(e.target.value)} placeholder="timeline" />
          </label>
          <label>
            Tipo
            <select value={tipo} onChange={(e) => setTipo(e.target.value)} disabled={!!pedidoId}>
              <option value="">Todos</option>
              {TIPOS.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </label>
          <label>
            Entidad
            <select value={entidad} onChange={(e) => setEntidad(e.target.value)} disabled={!!pedidoId}>
              <option value="">Todas</option>
              <option value="PEDIDO">Pedido</option>
              <option value="PRODUCTO">Producto</option>
            </select>
          </label>
          <div className="actions" style={{ marginTop: 'auto' }}>
            <button className="btn btn-primary">Buscar</button>
            <button type="button" className="btn btn-ghost" onClick={() => { setPedidoId(''); setTipo(''); setEntidad('') }}>
              Limpiar
            </button>
          </div>
        </form>
      </Card>

      <Card title={modo === 'timeline' ? `Timeline del pedido #${pedidoId} (más antiguo primero)` : 'Últimos eventos'}>
        {eventos.length === 0 ? (
          <p className="muted">Sin eventos.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Ocurrió</th>
                <th>Tipo</th>
                <th>Descripción</th>
                <th>Usuario</th>
                <th>Kafka</th>
              </tr>
            </thead>
            <tbody>
              {eventos.map((e) => (
                <tr key={e.id}>
                  <td className="nowrap">{fecha(e.ocurridoEn)}</td>
                  <td>
                    <span className={`chip ${e.entidad === 'PRODUCTO' ? 'chip-alt' : ''}`}>{e.tipo}</span>
                  </td>
                  <td>{e.descripcion}</td>
                  <td className="small">{e.usuario ?? '—'}</td>
                  <td className="small nowrap" title={`eventId ${e.eventId}`}>
                    <code>
                      {e.topico}-{e.particion}@{e.offset}
                    </code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  )
}
