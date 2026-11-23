import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { GrupoDetalle, GrupoResumen, KafkaCluster, KafkaMensaje, TopicoDetalle, TopicoResumen } from '../api/types'
import { useApi } from '../api/useApi'
import { Card, ErrorBox } from '../components/ui'

const DIA_MS = 86_400_000
const retencion = (ms?: string) =>
  !ms ? '—' : Number(ms) < 0 ? 'infinita' : Number(ms) % DIA_MS === 0 ? `${Number(ms) / DIA_MS} días` : `${ms} ms`

/** Ruta /admin/kafka (Admin): consume ms-kafka-admin (AdminClient). */
export function AdminKafkaPage() {
  const api = useApi()
  const [cluster, setCluster] = useState<KafkaCluster | null>(null)
  const [topicos, setTopicos] = useState<TopicoResumen[]>([])
  const [grupos, setGrupos] = useState<GrupoResumen[]>([])
  const [topico, setTopico] = useState<TopicoDetalle | null>(null)
  const [grupo, setGrupo] = useState<GrupoDetalle | null>(null)
  const [mensajes, setMensajes] = useState<{ topico: string; items: KafkaMensaje[] } | null>(null)
  const [nuevo, setNuevo] = useState({ nombre: '', particiones: 3, replicas: 2, dias: 7 })
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(() => {
    Promise.all([api.kafka.cluster(), api.kafka.topicos(), api.kafka.grupos()])
      .then(([c, t, g]) => {
        setCluster(c)
        setTopicos(t)
        setGrupos(g)
        setError(null)
      })
      .catch(setError)
  }, [api])

  useEffect(() => {
    load()
    const t = setInterval(load, 10000)
    return () => clearInterval(t)
  }, [load])

  const run = async (fn: () => Promise<unknown>, ok?: string) => {
    try {
      setError(null)
      await fn()
      if (ok) setNotice(ok)
      load()
    } catch (e) {
      setError(e)
    }
  }

  const verTopico = (n: string) => run(async () => setTopico(await api.kafka.topico(n)))
  const verGrupo = (id: string) => run(async () => setGrupo(await api.kafka.grupo(id)))
  const verMensajes = (n: string) => run(async () => setMensajes({ topico: n, items: await api.kafka.mensajes(n, 10) }))

  const crear = (e: FormEvent) => {
    e.preventDefault()
    run(async () => {
      setTopico(
        await api.kafka.crear({
          nombre: nuevo.nombre.trim(),
          particiones: nuevo.particiones,
          replicas: nuevo.replicas,
          configs: { 'retention.ms': String(nuevo.dias * DIA_MS) },
        }),
      )
      setNuevo({ ...nuevo, nombre: '' })
    }, `Tópico ${nuevo.nombre} creado`)
  }

  const cambiarRetencion = (t: TopicoDetalle) => {
    const dias = prompt(`Nueva retención de ${t.nombre} (días):`, String(Number(t.configs['retention.ms']) / DIA_MS))
    if (!dias || Number(dias) <= 0) return
    run(async () => setTopico(await api.kafka.configs(t.nombre, { 'retention.ms': String(Number(dias) * DIA_MS) })),
      `Retención de ${t.nombre}: ${dias} días`)
  }

  const aumentar = (t: TopicoDetalle) => {
    const total = prompt(`Particiones de ${t.nombre} (actual ${t.particiones.length}; solo se puede aumentar):`,
      String(t.particiones.length + 1))
    if (!total) return
    run(async () => setTopico(await api.kafka.particiones(t.nombre, Number(total))),
      `${t.nombre}: ahora ${total} particiones`)
  }

  const eliminar = (n: string) => {
    if (!confirm(`¿Eliminar el tópico ${n} y TODOS sus mensajes?`)) return
    run(async () => {
      await api.kafka.eliminar(n)
      setTopico(null)
    }, `Tópico ${n} eliminado`)
  }

  return (
    <div className="page">
      <h1>Kafka</h1>
      <ErrorBox error={error} />
      {notice && <div className="alert alert-info">{notice}</div>}

      {cluster && (
        <p className="muted">
          Clúster <code>{cluster.clusterId}</code> · {cluster.brokers.length} broker(s):{' '}
          {cluster.brokers.map((b) => `#${b.id} ${b.host}:${b.port}${b.id === cluster.controllerId ? ' (controlador)' : ''}`).join(' · ')}
        </p>
      )}

      <div className="split">
        <Card title="Tópicos">
          <table>
            <thead>
              <tr>
                <th>Nombre</th>
                <th className="num">Particiones</th>
                <th className="num">Réplicas</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {topicos.map((t) => (
                <tr key={t.nombre} className={topico?.nombre === t.nombre ? 'selected' : ''}>
                  <td>
                    <code>{t.nombre}</code>
                  </td>
                  <td className="num">{t.particiones}</td>
                  <td className="num">{t.replicas}</td>
                  <td className="actions-cell">
                    <button className="btn btn-ghost" onClick={() => verTopico(t.nombre)}>Detalle</button>
                    <button className="btn btn-ghost" onClick={() => verMensajes(t.nombre)}>Mensajes</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <form className="form-row" onSubmit={crear} style={{ marginTop: '1rem' }}>
            <label style={{ flex: 2 }}>
              Nuevo tópico
              <input value={nuevo.nombre} onChange={(e) => setNuevo({ ...nuevo, nombre: e.target.value })} placeholder="pedidos.prueba" required pattern="[a-zA-Z0-9._\-]+" />
            </label>
            <label>
              Particiones
              <input type="number" min={1} max={50} value={nuevo.particiones} onChange={(e) => setNuevo({ ...nuevo, particiones: Number(e.target.value) })} />
            </label>
            <label>
              Réplicas
              <input type="number" min={1} max={3} value={nuevo.replicas} onChange={(e) => setNuevo({ ...nuevo, replicas: Number(e.target.value) })} />
            </label>
            <label>
              Retención (días)
              <input type="number" min={1} value={nuevo.dias} onChange={(e) => setNuevo({ ...nuevo, dias: Number(e.target.value) })} />
            </label>
            <div className="actions" style={{ marginTop: 'auto' }}>
              <button className="btn btn-primary">Crear</button>
            </div>
          </form>
        </Card>

        <Card title="Consumer groups">
          <table>
            <thead>
              <tr>
                <th>Grupo</th>
                <th>Estado</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {grupos.map((g) => (
                <tr key={g.grupoId} className={grupo?.grupoId === g.grupoId ? 'selected' : ''}>
                  <td>
                    <code>{g.grupoId}</code>
                  </td>
                  <td className={g.estado === 'STABLE' ? '' : 'warn'}>{g.estado}</td>
                  <td className="actions-cell">
                    <button className="btn btn-ghost" onClick={() => verGrupo(g.grupoId)}>Detalle</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {grupo && (
            <>
              <h3 style={{ marginTop: '1rem' }}>
                {grupo.grupoId} · LAG total <span className={grupo.lagTotal ? 'warn' : ''}>{grupo.lagTotal}</span>
              </h3>
              <p className="muted small">
                {grupo.miembros.length} consumidor(es) · asignador {grupo.asignador || '—'} · coordinador {grupo.coordinador}
              </p>
              <table>
                <thead>
                  <tr>
                    <th>Consumidor</th>
                    <th>Particiones asignadas</th>
                  </tr>
                </thead>
                <tbody>
                  {grupo.miembros.map((m) => (
                    <tr key={m.consumerId}>
                      <td className="small">{m.clientId} <span className="muted">{m.host}</span></td>
                      <td className="small">{m.particiones.join(', ') || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <table style={{ marginTop: '.75rem' }}>
                <thead>
                  <tr>
                    <th>Partición</th>
                    <th className="num">Offset confirmado</th>
                    <th className="num">Fin</th>
                    <th className="num">LAG</th>
                  </tr>
                </thead>
                <tbody>
                  {grupo.offsets.map((o) => (
                    <tr key={`${o.topico}-${o.particion}`}>
                      <td><code>{o.topico}-{o.particion}</code></td>
                      <td className="num">{o.offsetConfirmado}</td>
                      <td className="num">{o.offsetFin}</td>
                      <td className={`num ${o.lag ? 'warn' : ''}`}>{o.lag}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </Card>
      </div>

      {topico && (
        <Card
          title={`Tópico ${topico.nombre}`}
          actions={
            <>
              <button className="btn btn-ghost" onClick={() => cambiarRetencion(topico)}>Cambiar retención</button>
              <button className="btn btn-ghost" onClick={() => aumentar(topico)}>Aumentar particiones</button>
              <button className="btn btn-ghost danger" onClick={() => eliminar(topico.nombre)}>Eliminar</button>
              <button className="btn btn-ghost" onClick={() => setTopico(null)}>Cerrar</button>
            </>
          }
        >
          <p className="muted small">
            {topico.mensajes} mensajes retenidos · retención {retencion(topico.configs['retention.ms'])} · cleanup.policy{' '}
            {topico.configs['cleanup.policy']} · min.insync.replicas {topico.configs['min.insync.replicas']}
          </p>
          <table>
            <thead>
              <tr>
                <th>Partición</th>
                <th>Líder</th>
                <th>Réplicas</th>
                <th>ISR (en sincronía)</th>
                <th className="num">Offset inicio</th>
                <th className="num">Offset fin</th>
              </tr>
            </thead>
            <tbody>
              {topico.particiones.map((p) => (
                <tr key={p.id}>
                  <td>{p.id}</td>
                  <td className={p.lider === null ? 'danger' : ''}>{p.lider === null ? 'sin líder' : `broker ${p.lider}`}</td>
                  <td>{p.replicas.join(', ')}</td>
                  <td className={p.isr.length < p.replicas.length ? 'warn' : ''}>{p.isr.join(', ')}</td>
                  <td className="num">{p.offsetInicio}</td>
                  <td className="num">{p.offsetFin}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <details style={{ marginTop: '.75rem' }}>
            <summary className="small">Configuración ({Object.keys(topico.configs).length})</summary>
            <pre>{JSON.stringify(topico.configs, null, 2)}</pre>
          </details>
        </Card>
      )}

      {mensajes && (
        <Card title={`Últimos mensajes de ${mensajes.topico}`} actions={<button className="btn btn-ghost" onClick={() => setMensajes(null)}>Cerrar</button>}>
          {mensajes.items.length === 0 ? (
            <p className="muted">Sin mensajes.</p>
          ) : (
            mensajes.items.map((m) => (
              <div key={`${m.particion}-${m.offset}`} className="dlq-msg">
                <div className="small">
                  partición {m.particion} · offset {m.offset} · clave <code>{m.clave ?? '—'}</code> ·{' '}
                  {new Date(m.timestamp).toLocaleString('es-CL')}
                  {m.cabeceras['kafka_dlt-exception-message'] && (
                    <div className="danger">
                      Error ({m.cabeceras['kafka_dlt-original-consumer-group']}): {m.cabeceras['kafka_dlt-exception-message']}
                    </div>
                  )}
                </div>
                <pre>{m.valor}</pre>
              </div>
            ))
          )}
        </Card>
      )}
    </div>
  )
}
