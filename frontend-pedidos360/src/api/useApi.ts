import { useMemo } from 'react'
import { useAuth } from '../auth/AuthContext'
import { apiFetch } from './client'
import type {
  Alert, DlqMessage, FlowStatus, Order, OrderRequest, OrderStatus, Overview,
  Product, ProductRequest, ReprocessResult, StatusChange,
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
    }
  }, [getToken])
}
