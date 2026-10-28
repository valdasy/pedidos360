import { useCallback, useEffect, useState } from 'react'
import type { Alert, DlqMessage, FlowStatus, Overview } from '../api/types'
import { useApi } from '../api/useApi'
import { Card, ErrorBox } from '../components/ui'

/** Ruta /admin/mq (Admin): consume ms-mq-admin. */
export function AdminMqPage() {
  const api = useApi()
  const [overview, setOverview] = useState<Overview | null>(null)
  const [flows, setFlows] = useState<FlowStatus[]>([])
  const [alerts, setAlerts] = useState<Alert[]>([])
  const [dlq, setDlq] = useState<{ flow: string; messages: DlqMessage[] } | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(() => {
    setError(null)
    Promise.all([api.mq.overview(), api.mq.flows(), api.mq.alerts()])
      .then(([o, f, a]) => {
        setOverview(o)
        setFlows(f)
        setAlerts(a)
      })
      .catch(setError)
  }, [api])

  useEffect(() => {
    load()
    const t = setInterval(load, 5000) // las estadísticas de RabbitMQ se refrescan cada ~5 s
    return () => clearInterval(t)
  }, [load])

  const verDlq = async (flow: string) => {
    try {
      setDlq({ flow, messages: await api.mq.peekDlq(flow) })
    } catch (e) {
      setError(e)
    }
  }

  const reprocesar = async (flow: string) => {
    try {
      const r = await api.mq.reprocess(flow)
      setNotice(`${flow}: ${r.moved} mensaje(s) devueltos a la cola${r.skipped.length ? `, ${r.skipped.length} omitidos (revisión manual)` : ''}`)
      setDlq(null)
      load()
    } catch (e) {
      setError(e)
    }
  }

  const purgar = async (flow: string) => {
    if (!confirm(`¿Eliminar definitivamente los mensajes de q.cmd.${flow}.dlq?`)) return
    try {
      await api.mq.purgeDlq(flow)
      setNotice(`DLQ de ${flow} purgada`)
      setDlq(null)
      load()
    } catch (e) {
      setError(e)
    }
  }

  return (
    <div className="page">
      <h1>Colas RabbitMQ</h1>
      <ErrorBox error={error} />
      {notice && <div className="alert alert-info">{notice}</div>}

      {overview && (
        <p className="muted">
          RabbitMQ {overview.rabbitmqVersion} · clúster <code>{overview.clusterName}</code> · {overview.nodes.length}{' '}
          nodo(s): {overview.nodes.map((n) => `${n.name} ${n.running ? '🟢' : '🔴'}`).join(', ')}
        </p>
      )}

      {alerts.length > 0 && (
        <ul className="alerts">
          {alerts.map((a, i) => (
            <li key={i} className={`alert-${a.level.toLowerCase()}`}>
              <strong>{a.level}</strong> {a.queue}: {a.message}
            </li>
          ))}
        </ul>
      )}

      <Card title="Flujos de trabajo" actions={<span className="muted small">se actualiza cada 5 s</span>}>
        <table>
          <thead>
            <tr>
              <th>Flujo</th>
              <th className="num">Ready</th>
              <th className="num">Unacked</th>
              <th className="num">Consumers</th>
              <th className="num">DLQ</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {flows.map((f) => (
              <tr key={f.flow}>
                <td>
                  <code>{f.main.name}</code>
                </td>
                <td className="num">{f.main.ready}</td>
                <td className="num">{f.main.unacked}</td>
                <td className={`num ${f.main.consumers === 0 ? 'danger' : ''}`}>{f.main.consumers}</td>
                <td className={`num ${f.dlq.ready > 0 ? 'warn' : ''}`}>{f.dlq.ready}</td>
                <td className="actions-cell">
                  <button className="btn btn-ghost" onClick={() => verDlq(f.flow)} disabled={!f.dlq.ready}>
                    Ver DLQ
                  </button>
                  <button className="btn btn-primary" onClick={() => reprocesar(f.flow)} disabled={!f.dlq.ready}>
                    Reprocesar
                  </button>
                  <button className="btn btn-ghost danger" onClick={() => purgar(f.flow)} disabled={!f.dlq.ready}>
                    Purgar
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      {dlq && (
        <Card title={`Mensajes en q.cmd.${dlq.flow}.dlq`} actions={<button className="btn btn-ghost" onClick={() => setDlq(null)}>Cerrar</button>}>
          {dlq.messages.length === 0 ? (
            <p className="muted">Vacía.</p>
          ) : (
            dlq.messages.map((m, i) => (
              <div key={i} className="dlq-msg">
                <div>
                  <strong>{m.type}</strong> · motivo <code>{m.reason}</code> · origen{' '}
                  <code>
                    {m.originalExchange} [{m.originalRoutingKey}]
                  </code>{' '}
                  · reprocesos {m.reprocessCount}
                </div>
                <pre>{m.payload}</pre>
              </div>
            ))
          )}
        </Card>
      )}
    </div>
  )
}
