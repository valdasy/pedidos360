import { useEffect, useRef, useState, type MouseEvent, type ReactNode } from 'react'

/**
 * Gráficos livianos en SVG/HTML, sin librerías: el proyecto no necesita más que barras y líneas.
 * Colores por ROL (ver index.css): --viz-1 serie principal, --viz-1-dark su promedio,
 * --viz-2 proyección. Una sola escala Y por gráfico; tooltip al pasar el mouse.
 */

/** Ancho real del contenedor (los SVG se dibujan en píxeles, no se estiran). */
function useAncho<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [ancho, setAncho] = useState(600)
  useEffect(() => {
    if (!ref.current) return
    const ro = new ResizeObserver(([e]) => setAncho(Math.max(240, Math.floor(e.contentRect.width))))
    ro.observe(ref.current)
    return () => ro.disconnect()
  }, [])
  return [ref, ancho] as const
}

/** Máximo "redondo" del eje (1, 2, 2,5 o 5 × 10^n) y sus 4 divisiones. */
function escala(max: number) {
  if (max <= 0) return { tope: 1, ticks: [0, 1] }
  const paso0 = max / 4
  const mag = 10 ** Math.floor(Math.log10(paso0))
  const paso = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((p) => p >= paso0) ?? 10 * mag
  const tope = paso * Math.ceil(max / paso)
  return { tope, ticks: Array.from({ length: Math.round(tope / paso) + 1 }, (_, i) => i * paso) }
}

/** $1,2 M · $350 mil · $900 */
export const clpCorto = (n: number) =>
  n >= 1_000_000
    ? `$${(n / 1_000_000).toLocaleString('es-CL', { maximumFractionDigits: 1 })} M`
    : n >= 1_000
      ? `$${Math.round(n / 1_000).toLocaleString('es-CL')} mil`
      : `$${Math.round(n).toLocaleString('es-CL')}`

const fechaCorta = (iso: string) => {
  const [, m, d] = iso.split('-')
  return `${d}/${m}`
}

/** Columna con las esquinas superiores redondeadas, apoyada en la línea base. */
function columna(x: number, y: number, w: number, h: number) {
  const r = Math.min(3, w / 2, h)
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`
}

export function Leyenda({ items }: { items: { color: string; texto: string; tipo?: 'barra' | 'linea' | 'punteada' }[] }) {
  return (
    <div className="legend">
      {items.map((i) => (
        <span key={i.texto} className="legend-item">
          <span
            className={`legend-swatch legend-${i.tipo ?? 'barra'}`}
            style={i.tipo === 'barra' || !i.tipo ? { background: i.color } : { borderColor: i.color }}
          />
          {i.texto}
        </span>
      ))}
    </div>
  )
}

function Tooltip({ x, y, ancho, children }: { x: number; y: number; ancho: number; children: ReactNode }) {
  // Se voltea a la izquierda cerca del borde derecho para no salirse de la tarjeta
  const izquierda = x > ancho - 190
  return (
    <div className="chart-tooltip" style={{ top: y, left: izquierda ? undefined : x + 12, right: izquierda ? ancho - x + 12 : undefined }}>
      {children}
    </div>
  )
}

type PuntoVenta = { fecha: string; pedidos: number; monto: number; promedioMovil7: number }
type PuntoProy = { fecha: string; monto: number; minimo: number; maximo: number }

/**
 * Ventas diarias (columnas) + promedio móvil de 7 días (línea) + proyección (línea punteada
 * con su banda de confianza). El último día real es HOY: todavía en curso, se dibuja más claro.
 */
export function GraficoVentas({ serie, proyeccion, alto = 280 }: { serie: PuntoVenta[]; proyeccion: PuntoProy[]; alto?: number }) {
  const [ref, ancho] = useAncho<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  const m = { izq: 64, der: 12, arr: 12, aba: 28 }
  const n = serie.length + proyeccion.length
  const banda = (ancho - m.izq - m.der) / Math.max(1, n)
  const { tope, ticks } = escala(
    Math.max(0, ...serie.map((s) => Math.max(s.monto, s.promedioMovil7)), ...proyeccion.map((p) => p.maximo)),
  )
  const y = (v: number) => m.arr + (alto - m.arr - m.aba) * (1 - v / tope)
  const cx = (i: number) => m.izq + banda * (i + 0.5)
  const base = y(0)
  const gap = banda > 6 ? 2 : banda > 3 ? 1 : 0
  const cadaCuantos = Math.max(1, Math.ceil(56 / banda))
  const todas = [...serie.map((s) => s.fecha), ...proyeccion.map((p) => p.fecha)]
  const linea = (pts: [number, number][]) => pts.map(([px, py], i) => `${i ? 'L' : 'M'}${px},${py}`).join('')

  const promedio = linea(serie.map((s, i) => [cx(i), y(s.promedioMovil7)]))
  const off = serie.length
  const proy = linea(proyeccion.map((p, i) => [cx(off + i), y(p.monto)]))
  const bandaProy =
    proyeccion.length > 0
      ? `${linea(proyeccion.map((p, i) => [cx(off + i), y(p.maximo)]))}${proyeccion
          .map((p, i) => [cx(off + i), y(p.minimo)] as [number, number])
          .reverse()
          .map(([px, py]) => `L${px},${py}`)
          .join('')}Z`
      : ''

  const onMove = (e: MouseEvent<SVGRectElement>) => {
    const r = e.currentTarget.getBoundingClientRect()
    const i = Math.floor((e.clientX - r.left) / banda)
    setHover(i >= 0 && i < n ? i : null)
  }

  const h = hover
  return (
    <div ref={ref} className="chart">
      <svg width={ancho} height={alto} role="img" aria-label="Ventas diarias, promedio de 7 días y proyección">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={m.izq} x2={ancho - m.der} y1={y(t)} y2={y(t)} className={t === 0 ? 'axis-base' : 'axis-grid'} />
            <text x={m.izq - 8} y={y(t)} className="axis-label" textAnchor="end" dominantBaseline="middle">
              {clpCorto(t)}
            </text>
          </g>
        ))}
        {serie.map((s, i) =>
          s.monto > 0 ? (
            <path
              key={s.fecha}
              d={columna(m.izq + banda * i + gap / 2, y(s.monto), Math.max(1, banda - gap), base - y(s.monto))}
              className={i === serie.length - 1 ? 'viz-col viz-col-hoy' : 'viz-col'}
              opacity={h === null || h === i ? 1 : 0.55}
            />
          ) : null,
        )}
        {bandaProy && <path d={bandaProy} className="viz-banda" />}
        {serie.length > 1 && <path d={promedio} className="viz-linea" />}
        {proyeccion.length > 0 && (
          <>
            <line x1={m.izq + banda * off} x2={m.izq + banda * off} y1={m.arr} y2={base} className="viz-divisor" />
            <text x={m.izq + banda * off + 4} y={m.arr + 10} className="axis-label">
              proyección →
            </text>
            <path d={proy} className="viz-proy" />
            {proyeccion.map((p, i) => (
              <circle key={p.fecha} cx={cx(off + i)} cy={y(p.monto)} r={h === off + i ? 5 : 3} className="viz-proy-punto" />
            ))}
          </>
        )}
        {todas.map((f, i) =>
          i % cadaCuantos === 0 ? (
            <text key={f} x={cx(i)} y={alto - 8} className="axis-label" textAnchor="middle">
              {fechaCorta(f)}
            </text>
          ) : null,
        )}
        {h !== null && <line x1={cx(h)} x2={cx(h)} y1={m.arr} y2={base} className="viz-guia" />}
        {h !== null && h < off && <circle cx={cx(h)} cy={y(serie[h].promedioMovil7)} r={4} className="viz-linea-punto" />}
        <rect x={m.izq} y={m.arr} width={Math.max(0, ancho - m.izq - m.der)} height={base - m.arr} fill="transparent" onMouseMove={onMove} onMouseLeave={() => setHover(null)} />
      </svg>
      {h !== null && (
        <Tooltip x={cx(h)} y={m.arr} ancho={ancho}>
          {h < off ? (
            <>
              <strong>
                {new Date(serie[h].fecha + 'T12:00:00').toLocaleDateString('es-CL', { weekday: 'long', day: 'numeric', month: 'short' })}
                {h === off - 1 && ' (hoy, en curso)'}
              </strong>
              <span>Ventas: {clpCorto(serie[h].monto)}</span>
              <span>Pedidos: {serie[h].pedidos}</span>
              <span className="muted">Promedio 7 días: {clpCorto(serie[h].promedioMovil7)}</span>
            </>
          ) : (
            <>
              <strong>
                {new Date(proyeccion[h - off].fecha + 'T12:00:00').toLocaleDateString('es-CL', { weekday: 'long', day: 'numeric', month: 'short' })}
              </strong>
              <span>Proyección: {clpCorto(proyeccion[h - off].monto)}</span>
              <span className="muted">
                Rango: {clpCorto(proyeccion[h - off].minimo)} – {clpCorto(proyeccion[h - off].maximo)}
              </span>
            </>
          )}
        </Tooltip>
      )}
    </div>
  )
}

/** Columnas verticales simples (una serie). Solo la mayor lleva su valor escrito. */
export function Columnas({
  items,
  formato,
  alto = 190,
}: {
  items: { etiqueta: string; valor: number; detalle?: string }[]
  formato: (n: number) => string
  alto?: number
}) {
  const [ref, ancho] = useAncho<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  const m = { izq: 56, der: 8, arr: 18, aba: 24 }
  const banda = (ancho - m.izq - m.der) / Math.max(1, items.length)
  const { tope, ticks } = escala(Math.max(0, ...items.map((i) => i.valor)))
  const y = (v: number) => m.arr + (alto - m.arr - m.aba) * (1 - v / tope)
  const base = y(0)
  const iMax = items.reduce((best, it, i) => (it.valor > items[best].valor ? i : best), 0)
  const w = Math.min(48, banda * 0.7)
  const cadaCuantos = Math.max(1, Math.ceil(30 / banda))

  return (
    <div ref={ref} className="chart">
      <svg width={ancho} height={alto} role="img">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={m.izq} x2={ancho - m.der} y1={y(t)} y2={y(t)} className={t === 0 ? 'axis-base' : 'axis-grid'} />
            <text x={m.izq - 8} y={y(t)} className="axis-label" textAnchor="end" dominantBaseline="middle">
              {formato(t)}
            </text>
          </g>
        ))}
        {items.map((it, i) => {
          const x = m.izq + banda * i + (banda - w) / 2
          return (
            <g key={it.etiqueta} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
              <rect x={m.izq + banda * i} y={m.arr} width={banda} height={base - m.arr} fill="transparent" />
              {it.valor > 0 && (
                <path d={columna(x, y(it.valor), w, base - y(it.valor))} className="viz-col" opacity={hover === null || hover === i ? 1 : 0.55} />
              )}
              {i === iMax && it.valor > 0 && (
                <text x={x + w / 2} y={y(it.valor) - 5} className="axis-label viz-valor" textAnchor="middle">
                  {formato(it.valor)}
                </text>
              )}
              {i % cadaCuantos === 0 && (
                <text x={x + w / 2} y={alto - 7} className="axis-label" textAnchor="middle">
                  {it.etiqueta}
                </text>
              )}
            </g>
          )
        })}
      </svg>
      {hover !== null && (
        <Tooltip x={m.izq + banda * (hover + 0.5)} y={m.arr} ancho={ancho}>
          <strong>{items[hover].etiqueta}</strong>
          <span>{formato(items[hover].valor)}</span>
          {items[hover].detalle && <span className="muted">{items[hover].detalle}</span>}
        </Tooltip>
      )}
    </div>
  )
}

/** Barras horizontales en HTML: etiqueta | barra | valor (todos los valores visibles). */
export function BarrasH({
  items,
  max,
}: {
  items: { etiqueta: ReactNode; valor: number; texto: ReactNode; detalle?: ReactNode; clase?: string }[]
  max?: number
}) {
  const tope = max ?? Math.max(0, ...items.map((i) => i.valor))
  return (
    <div className="hbars">
      {items.map((it, i) => (
        <div key={i} className="hbar-row">
          <span className="hbar-label">{it.etiqueta}</span>
          <span className="hbar-track">
            <span className={`hbar-fill ${it.clase ?? ''}`} style={{ width: `${tope ? Math.max(1.5, (100 * it.valor) / tope) : 0}%` }} />
          </span>
          <span className="hbar-value">
            {it.texto}
            {it.detalle && <span className="muted small"> {it.detalle}</span>}
          </span>
        </div>
      ))}
    </div>
  )
}
