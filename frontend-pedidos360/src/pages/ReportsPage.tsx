import { useCallback, useEffect, useState } from 'react'
import type { Kpis, OrderStatus, TopProducto, VentaDiaria } from '../api/types'
import { useApi } from '../api/useApi'
import { Card, clp, ErrorBox, Stat, STATUS_LABEL } from '../components/ui'

const ORDEN: OrderStatus[] = ['CREADO', 'ACEPTADO', 'EN_PREPARACION', 'DESPACHADO', 'ENTREGADO', 'CANCELADO']

const minutos = (m: number | null) =>
  m === null
    ? '—'
    : m < 1
      ? `${Math.round(m * 60)} s`
      : m < 60
        ? `${m.toLocaleString('es-CL')} min`
        : `${(m / 60).toFixed(1).replace('.', ',')} h`

/** Barra horizontal simple: proporción de un valor respecto al máximo. */
function Barra({ valor, max }: { valor: number; max: number }) {
  return (
    <div className="bar-track">
      <div className="bar-fill" style={{ width: `${max ? Math.max(2, (100 * valor) / max) : 0}%` }} />
    </div>
  )
}

/**
 * Ruta /reports (Admin, Operador): consume ms-report.
 * Los datos NO vienen de la base de orders: ms-report los construye leyendo el tópico Kafka
 * "pedidos.eventos" (consumer group pedidos360-report). Se refresca cada 5 s para ver cómo
 * cambian al crear o avanzar un pedido.
 */
export function ReportsPage() {
  const api = useApi()
  const [kpis, setKpis] = useState<Kpis | null>(null)
  const [top, setTop] = useState<TopProducto[]>([])
  const [ventas, setVentas] = useState<VentaDiaria[]>([])
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(() => {
    Promise.all([api.report.kpis(), api.report.topProductos(5), api.report.ventasDiarias(14)])
      .then(([k, t, v]) => {
        setKpis(k)
        setTop(t)
        setVentas(v)
        setError(null)
      })
      .catch(setError)
  }, [api])

  useEffect(() => {
    load()
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [load])

  const maxEstado = Math.max(0, ...Object.values(kpis?.pedidosPorEstado ?? {}))
  const maxTop = Math.max(0, ...top.map((p) => p.unidades))
  const maxVenta = Math.max(0, ...ventas.map((v) => v.monto))

  return (
    <div className="page">
      <div className="page-header">
        <h1>Reportes</h1>
        <span className="muted small">
          Fuente: Kafka <code>pedidos.eventos</code> · {kpis?.eventosProcesados ?? 0} eventos procesados · se actualiza
          cada 5 s
        </span>
      </div>
      <ErrorBox error={error} />

      {kpis && (
        <div className="stats">
          <Stat label="Ventas confirmadas" value={clp(kpis.ventasConfirmadas)} hint={`${kpis.pedidosConfirmados} pedidos`} />
          <Stat label="Ticket promedio" value={clp(kpis.ticketPromedio)} />
          <Stat label="Pedidos totales" value={kpis.totalPedidos} />
          <Stat label="Cancelación" value={`${kpis.tasaCancelacion.toLocaleString('es-CL')} %`} hint={`${kpis.cancelados} cancelados`} />
          <Stat label="Tiempo hasta aceptar" value={minutos(kpis.tiempoAceptacionPromedioMin)} hint="promedio" />
          <Stat label="Tiempo de entrega" value={minutos(kpis.tiempoEntregaPromedioMin)} hint="creado → entregado" />
        </div>
      )}

      <div className="split">
        <Card title="Pedidos por estado">
          <table>
            <tbody>
              {ORDEN.filter((e) => kpis?.pedidosPorEstado[e]).map((e) => (
                <tr key={e}>
                  <td style={{ width: '35%' }}>
                    <span className={`badge badge-${e.toLowerCase()}`}>{STATUS_LABEL[e]}</span>
                  </td>
                  <td>
                    <Barra valor={kpis!.pedidosPorEstado[e]} max={maxEstado} />
                  </td>
                  <td className="num" style={{ width: '3rem' }}>
                    {kpis!.pedidosPorEstado[e]}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!kpis?.totalPedidos && <p className="muted">Aún no hay pedidos.</p>}
        </Card>

        <Card title="Productos más vendidos">
          {top.length === 0 ? (
            <p className="muted">Sin ventas confirmadas todavía.</p>
          ) : (
            <table>
              <tbody>
                {top.map((p) => (
                  <tr key={p.productoId}>
                    <td style={{ width: '35%' }}>{p.nombre}</td>
                    <td>
                      <Barra valor={p.unidades} max={maxTop} />
                    </td>
                    <td className="num">{p.unidades} u.</td>
                    <td className="num">{clp(p.monto)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </div>

      <Card title="Ventas confirmadas por día (últimos 14 días)">
        {ventas.length === 0 ? (
          <p className="muted">Sin ventas en el período.</p>
        ) : (
          <table>
            <tbody>
              {ventas.map((v) => (
                <tr key={v.fecha}>
                  <td style={{ width: '20%' }}>{new Date(v.fecha + 'T12:00:00').toLocaleDateString('es-CL')}</td>
                  <td>
                    <Barra valor={v.monto} max={maxVenta} />
                  </td>
                  <td className="num">{v.pedidos} ped.</td>
                  <td className="num">{clp(v.monto)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  )
}
