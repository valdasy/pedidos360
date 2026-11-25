import { useCallback, useEffect, useState, type ReactNode } from 'react'
import type { Analitica, Kpis, OrderStatus, TopProducto } from '../api/types'
import { useApi } from '../api/useApi'
import { BarrasH, clpCorto, Columnas, GraficoVentas, Leyenda } from '../components/charts'
import { Card, clp, ErrorBox, STATUS_LABEL } from '../components/ui'

const ORDEN: OrderStatus[] = ['CREADO', 'ACEPTADO', 'EN_PREPARACION', 'DESPACHADO', 'ENTREGADO', 'CANCELADO']
const RANGOS = [
  { dias: 7, proyeccion: 7, texto: '7 días' },
  { dias: 30, proyeccion: 7, texto: '30 días' },
  { dias: 90, proyeccion: 14, texto: '90 días' },
]

const minutos = (m: number | null) =>
  m === null
    ? '—'
    : m < 1
      ? `${Math.round(m * 60)} s`
      : m < 60
        ? `${m.toLocaleString('es-CL')} min`
        : `${(m / 60).toFixed(1).replace('.', ',')} h`

const pct = (a: number, b: number) => (b ? Math.round((1000 * a) / b) / 10 : 0)

/** Variación contra el período anterior: flecha + signo + texto (no depende solo del color). */
function Delta({ actual, anterior }: { actual: number; anterior: number }) {
  if (!anterior) return <span className="delta delta-neutro">sin período anterior</span>
  const v = ((actual - anterior) / anterior) * 100
  const r = Math.round(v * 10) / 10
  if (r === 0) return <span className="delta delta-neutro">= igual que el período anterior</span>
  return (
    <span className={`delta ${r > 0 ? 'delta-sube' : 'delta-baja'}`}>
      {r > 0 ? '▲ +' : '▼ '}
      {r.toLocaleString('es-CL')} % vs. período anterior
    </span>
  )
}

function Kpi({ label, value, children }: { label: string; value: string | number; children?: ReactNode }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {children}
    </div>
  )
}

/**
 * Ruta /reports (Admin, Operador): consume ms-report.
 * Los datos NO vienen de la base de orders: ms-report los construye leyendo el tópico Kafka
 * "pedidos.eventos" (consumer group pedidos360-report). Se refresca cada 10 s.
 */
export function ReportsPage() {
  const api = useApi()
  const [rango, setRango] = useState(RANGOS[1])
  const [kpis, setKpis] = useState<Kpis | null>(null)
  const [top, setTop] = useState<TopProducto[]>([])
  const [ana, setAna] = useState<Analitica | null>(null)
  const [tabla, setTabla] = useState(false)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(() => {
    Promise.all([api.report.kpis(), api.report.topProductos(6), api.report.analitica(rango.dias, rango.proyeccion)])
      .then(([k, t, a]) => {
        setKpis(k)
        setTop(t)
        setAna(a)
        setError(null)
      })
      .catch(setError)
  }, [api, rango])

  useEffect(() => {
    load()
    const t = setInterval(load, 10000)
    return () => clearInterval(t)
  }, [load])

  const p = ana?.periodo
  const pa = ana?.periodoAnterior
  const totalTop = top.reduce((s, t) => s + t.monto, 0)
  const proyTotal = ana?.proyeccion.reduce((s, x) => s + x.monto, 0) ?? 0
  const horas = ana ? ana.porHora.filter((h) => h.hora >= 7 && h.hora <= 21) : []
  const cierre = ana?.cierreMes
  const mesTexto = cierre
    ? new Date(cierre.mes + '-15T12:00:00').toLocaleDateString('es-CL', { month: 'long', year: 'numeric' })
    : ''

  return (
    <div className="page">
      <div className="page-header">
        <h1>Reportes</h1>
        <div className="seg" role="group" aria-label="Período">
          {RANGOS.map((r) => (
            <button key={r.dias} className={r.dias === rango.dias ? 'seg-on' : ''} onClick={() => setRango(r)}>
              {r.texto}
            </button>
          ))}
        </div>
      </div>
      <p className="muted small" style={{ marginTop: '-.5rem' }}>
        Fuente: Kafka <code>pedidos.eventos</code> · {kpis?.eventosProcesados.toLocaleString('es-CL') ?? 0} eventos
        procesados · solo ventas confirmadas (aceptadas y no canceladas) · se actualiza cada 10 s
      </p>
      <ErrorBox error={error} />

      {p && pa && ana && (
        <div className="stats">
          <Kpi label={`Ventas (${rango.texto})`} value={clp(p.ventas)}>
            <Delta actual={p.ventas} anterior={pa.ventas} />
          </Kpi>
          <Kpi label="Pedidos confirmados" value={p.pedidos.toLocaleString('es-CL')}>
            <Delta actual={p.pedidos} anterior={pa.pedidos} />
          </Kpi>
          <Kpi label="Ticket promedio" value={clp(p.ticketPromedio)}>
            <Delta actual={p.ticketPromedio} anterior={pa.ticketPromedio} />
          </Kpi>
          <Kpi label="Unidades vendidas" value={p.unidades.toLocaleString('es-CL')}>
            <span className="stat-hint">{p.clientes} clientes distintos</span>
          </Kpi>
          <Kpi label={`Proyección próximos ${rango.proyeccion} días`} value={clp(proyTotal)}>
            <span className="stat-hint">
              tendencia {ana.pendienteDiaria >= 0 ? '▲ +' : '▼ '}
              {clpCorto(Math.abs(ana.pendienteDiaria))} por día
            </span>
          </Kpi>
        </div>
      )}

      {ana && (
        <Card
          title="Ventas diarias y proyección"
          actions={
            <button className="btn btn-ghost" onClick={() => setTabla((t) => !t)}>
              {tabla ? 'Ver gráfico' : 'Ver tabla'}
            </button>
          }
        >
          <Leyenda
            items={[
              { color: 'var(--viz-1)', texto: 'Venta del día' },
              { color: 'var(--viz-1-dark)', texto: 'Promedio 7 días', tipo: 'linea' },
              { color: 'var(--viz-2)', texto: 'Proyección (tendencia × día de la semana)', tipo: 'punteada' },
            ]}
          />
          {tabla ? (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Fecha</th>
                    <th className="num">Pedidos</th>
                    <th className="num">Ventas</th>
                    <th className="num">Promedio 7 días</th>
                  </tr>
                </thead>
                <tbody>
                  {[...ana.serie].reverse().map((s) => (
                    <tr key={s.fecha}>
                      <td>{new Date(s.fecha + 'T12:00:00').toLocaleDateString('es-CL')}</td>
                      <td className="num">{s.pedidos}</td>
                      <td className="num">{clp(s.monto)}</td>
                      <td className="num">{clp(s.promedioMovil7)}</td>
                    </tr>
                  ))}
                  {ana.proyeccion.map((x) => (
                    <tr key={x.fecha} className="muted">
                      <td>{new Date(x.fecha + 'T12:00:00').toLocaleDateString('es-CL')} (proyección)</td>
                      <td className="num">—</td>
                      <td className="num">{clp(x.monto)}</td>
                      <td className="num">
                        {clpCorto(x.minimo)} – {clpCorto(x.maximo)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <GraficoVentas serie={ana.serie} proyeccion={ana.proyeccion} />
          )}
          <p className="hint">
            La proyección ajusta una recta (mínimos cuadrados) a las ventas de los días completos y la multiplica por
            cuánto vende cada día de la semana respecto del promedio. La banda es ± una desviación estándar del error.
          </p>
        </Card>
      )}

      {cierre && (
        <Card title={`Cierre estimado de ${mesTexto}`}>
          <div className="cierre">
            <div className="cierre-cifra">
              <span className="stat-value">{clp(cierre.estimadoCierre)}</span>
              <span className="muted small">
                día {cierre.diasTranscurridos} de {cierre.diasDelMes}
              </span>
            </div>
            <div className="cierre-barra" aria-label="Vendido y proyectado del mes">
              <span className="cierre-real" style={{ flexGrow: cierre.ventasALaFecha }} />
              <span className="cierre-proy" style={{ flexGrow: cierre.proyectadoRestante }} />
            </div>
            <Leyenda
              items={[
                { color: 'var(--viz-1)', texto: `Vendido a la fecha: ${clp(cierre.ventasALaFecha)}` },
                { color: 'var(--viz-2)', texto: `Proyectado para los días que faltan: ${clp(cierre.proyectadoRestante)}` },
              ]}
            />
          </div>
        </Card>
      )}

      <div className="split">
        <Card title="Productos más vendidos (histórico)">
          {top.length === 0 ? (
            <p className="muted">Sin ventas confirmadas todavía.</p>
          ) : (
            <BarrasH
              items={top.map((t) => ({
                etiqueta: t.nombre,
                valor: t.unidades,
                texto: `${t.unidades.toLocaleString('es-CL')} u.`,
                detalle: `${clpCorto(t.monto)} · ${pct(t.monto, totalTop).toLocaleString('es-CL')} %`,
              }))}
            />
          )}
        </Card>

        {ana && (
          <Card title={`Embudo de pedidos (${rango.texto})`}>
            <BarrasH
              max={ana.embudo.creados}
              items={[
                { etiqueta: 'Creados', valor: ana.embudo.creados, texto: ana.embudo.creados, detalle: '100 %' },
                {
                  etiqueta: 'Aceptados',
                  valor: ana.embudo.aceptados,
                  texto: ana.embudo.aceptados,
                  detalle: `${pct(ana.embudo.aceptados, ana.embudo.creados).toLocaleString('es-CL')} %`,
                },
                {
                  etiqueta: 'Entregados',
                  valor: ana.embudo.entregados,
                  texto: ana.embudo.entregados,
                  detalle: `${pct(ana.embudo.entregados, ana.embudo.creados).toLocaleString('es-CL')} %`,
                },
                {
                  etiqueta: 'Cancelados',
                  valor: ana.embudo.cancelados,
                  texto: ana.embudo.cancelados,
                  detalle: `${pct(ana.embudo.cancelados, ana.embudo.creados).toLocaleString('es-CL')} %`,
                  clase: 'hbar-cancelado',
                },
              ]}
            />
            <p className="hint">Pedidos creados en el período y hasta qué etapa llegaron.</p>
          </Card>
        )}
      </div>

      {ana && (
        <div className="split">
          <Card title="Venta promedio por día de la semana">
            <Columnas
              items={ana.porDiaSemana.map((d) => ({
                etiqueta: d.nombre.slice(0, 3),
                valor: d.promedioDiario,
                detalle: `${d.pedidos} pedidos · total ${clpCorto(d.monto)}`,
              }))}
              formato={clpCorto}
            />
          </Card>
          <Card title="Pedidos por hora del día">
            <Columnas
              items={horas.map((h) => ({
                etiqueta: `${h.hora}h`,
                valor: h.pedidos,
                detalle: `${clpCorto(h.monto)} en ventas`,
              }))}
              formato={(n) => n.toLocaleString('es-CL')}
            />
          </Card>
        </div>
      )}

      {kpis && (
        <div className="split">
          <Card title="Pedidos por estado (histórico)">
            <BarrasH
              items={ORDEN.filter((e) => kpis.pedidosPorEstado[e]).map((e) => ({
                etiqueta: <span className={`badge badge-${e.toLowerCase()}`}>{STATUS_LABEL[e]}</span>,
                valor: kpis.pedidosPorEstado[e],
                texto: kpis.pedidosPorEstado[e].toLocaleString('es-CL'),
                clase: e === 'CANCELADO' ? 'hbar-cancelado' : '',
              }))}
            />
            {!kpis.totalPedidos && <p className="muted">Aún no hay pedidos.</p>}
          </Card>
          <Card title="Indicadores de operación (histórico)">
            <table>
              <tbody>
                <tr>
                  <td>Pedidos totales</td>
                  <td className="num">{kpis.totalPedidos.toLocaleString('es-CL')}</td>
                </tr>
                <tr>
                  <td>Ventas confirmadas</td>
                  <td className="num">{clp(kpis.ventasConfirmadas)}</td>
                </tr>
                <tr>
                  <td>Tasa de cancelación</td>
                  <td className="num">
                    {kpis.tasaCancelacion.toLocaleString('es-CL')} % ({kpis.cancelados})
                  </td>
                </tr>
                <tr>
                  <td>Tiempo hasta aceptar (promedio)</td>
                  <td className="num">{minutos(kpis.tiempoAceptacionPromedioMin)}</td>
                </tr>
                <tr>
                  <td>Tiempo de entrega, creado → entregado (promedio)</td>
                  <td className="num">{minutos(kpis.tiempoEntregaPromedioMin)}</td>
                </tr>
              </tbody>
            </table>
          </Card>
        </div>
      )}
    </div>
  )
}
