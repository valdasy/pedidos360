import { useMemo } from 'react'
import { useAuth } from '../auth/AuthContext'
import { apiFetch } from './client'
import type {
  Alert, AuditEvent, AuditResumen, CrearTopicoRequest, DlqMessage, FlowStatus, GrupoDetalle, GrupoResumen,
  KafkaCluster, KafkaMensaje, Kpis, Order, OrderRequest, OrderStatus, Overview, Product, ProductRequest,
  ReprocessResult, StatusChange, TopicoDetalle, TopicoResumen, TopProducto, VentaDiaria,
} from './types'

/** Funciones de la API ya conectadas al token del usuario actual. */
export function useApi() {
  const { getToken } = useAuth()

  return useMemo(() => {
    const call = <T,>(path: string, init?: RequestInit) => apiFetch<T>(getToken, path, init)
    const json = (method: string, body: unknown): RequestInit => ({ method, body: JSON.stringify(body) })

    return {
      orders: {
        list: (estado?: OrderStatus) => call<Order[]>(`/api/orders${estado ? `?estado=${estado}` : ''}`),
        get: (id: number) => call<Order>(`/api/orders/${id}`),
        history: (id: number) => call<StatusChange[]>(`/api/orders/${id}/history`),
        create: (req: OrderRequest) => call<Order>('/api/orders', json('POST', req)),
        changeStatus: (id: number, estado: OrderStatus) =>
          call<Order>(`/api/orders/${id}/status`, json('PATCH', { estado })),
        remove: (id: number) => call<void>(`/api/orders/${id}`, { method: 'DELETE' }),
      },
      catalog: {
        list: () => call<Product[]>('/api/catalog/products'),
        create: (req: ProductRequest) => call<Product>('/api/catalog/products', json('POST', req)),
        update: (id: number, req: ProductRequest) => call<Product>(`/api/catalog/products/${id}`, json('PUT', req)),
        remove: (id: number) => call<void>(`/api/catalog/products/${id}`, { method: 'DELETE' }),
      },
      mq: {
        overview: () => call<Overview>('/api/mq-admin/overview'),
        flows: () => call<FlowStatus[]>('/api/mq-admin/flows'),
        alerts: () => call<Alert[]>('/api/mq-admin/alerts'),
        peekDlq: (flow: string) => call<DlqMessage[]>(`/api/mq-admin/flows/${flow}/dlq/messages?count=20`),
        reprocess: (flow: string) =>
          call<ReprocessResult>(`/api/mq-admin/flows/${flow}/dlq/reprocess?count=50`, { method: 'POST' }),
        purgeDlq: (flow: string) => call<void>(`/api/mq-admin/flows/${flow}/dlq/messages`, { method: 'DELETE' }),
      },
      // ---------- Kafka (Evaluación 3) ----------
      report: {
        kpis: () => call<Kpis>('/api/report/kpis'),
        topProductos: (limit = 5) => call<TopProducto[]>(`/api/report/top-productos?limit=${limit}`),
        ventasDiarias: (dias = 14) => call<VentaDiaria[]>(`/api/report/ventas-diarias?dias=${dias}`),
      },
      audit: {
        resumen: () => call<AuditResumen>('/api/audit/resumen'),
        buscar: (f: { entidad?: string; tipo?: string; usuario?: string; limit?: number }) => {
          const q = new URLSearchParams()
          Object.entries(f).forEach(([k, v]) => v !== undefined && v !== '' && q.set(k, String(v)))
          return call<AuditEvent[]>(`/api/audit/events?${q}`)
        },
        timeline: (pedidoId: number) => call<AuditEvent[]>(`/api/audit/pedidos/${pedidoId}/timeline`),
      },
      kafka: {
        cluster: () => call<KafkaCluster>('/api/kafka-admin/cluster'),
        topicos: () => call<TopicoResumen[]>('/api/kafka-admin/topics'),
        topico: (n: string) => call<TopicoDetalle>(`/api/kafka-admin/topics/${encodeURIComponent(n)}`),
        crear: (req: CrearTopicoRequest) => call<TopicoDetalle>('/api/kafka-admin/topics', json('POST', req)),
        configs: (n: string, configs: Record<string, string>) =>
          call<TopicoDetalle>(`/api/kafka-admin/topics/${encodeURIComponent(n)}/configs`, json('PATCH', { configs })),
        particiones: (n: string, total: number) =>
          call<TopicoDetalle>(`/api/kafka-admin/topics/${encodeURIComponent(n)}/partitions`, json('POST', { total })),
        eliminar: (n: string) => call<void>(`/api/kafka-admin/topics/${encodeURIComponent(n)}`, { method: 'DELETE' }),
        mensajes: (n: string, limit = 10) =>
          call<KafkaMensaje[]>(`/api/kafka-admin/topics/${encodeURIComponent(n)}/messages?limit=${limit}`),
        grupos: () => call<GrupoResumen[]>('/api/kafka-admin/groups'),
        grupo: (id: string) => call<GrupoDetalle>(`/api/kafka-admin/groups/${encodeURIComponent(id)}`),
      },
    }
  }, [getToken])
}
