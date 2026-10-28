/** Mismo contrato JSON que exponen los microservicios (ver Swagger de cada uno). */

export type OrderStatus = 'CREADO' | 'ACEPTADO' | 'EN_PREPARACION' | 'DESPACHADO' | 'ENTREGADO' | 'CANCELADO'

export const ORDER_STATUSES: OrderStatus[] = ['CREADO', 'ACEPTADO', 'EN_PREPARACION', 'DESPACHADO', 'ENTREGADO', 'CANCELADO']

export interface OrderItem {
  productoId: number
  nombre: string
  precioUnitario: number
  cantidad: number
  subtotal: number
}

export interface Order {
  id: number
  clienteNombre: string
  clienteEmail: string
  estado: OrderStatus
  siguientesEstados: OrderStatus[]
  total: number
  items: OrderItem[]
  creadoEn: string
  actualizadoEn: string
  aceptadoEn: string | null
  entregadoEn: string | null
}

export interface OrderRequest {
  clienteNombre: string
  clienteEmail: string
  items: { productoId: number; cantidad: number }[]
}

export interface StatusChange {
  estadoAnterior: OrderStatus | null
  estadoNuevo: OrderStatus
  cambiadoEn: string
}

export interface Product {
  id: number
  sku: string
  nombre: string
  descripcion: string | null
  precio: number
  stock: number
  actualizadoEn: string
}

export type ProductRequest = Omit<Product, 'id' | 'actualizadoEn'>

export interface QueueStatus {
  name: string
  ready: number
  unacked: number
  consumers: number
  publishRate: number
  deliverRate: number
}

export interface FlowStatus {
  flow: 'email' | 'kitchen' | 'invoice'
  main: QueueStatus
  dlq: QueueStatus
}

export interface Alert {
  level: 'WARNING' | 'CRITICAL'
  queue: string
  message: string
}

export interface DlqMessage {
  messageId: string | null
  type: string | null
  reason: string | null
  originalExchange: string | null
  originalRoutingKey: string | null
  deathCount: number
  reprocessCount: number
  payload: string
}

export interface ReprocessResult {
  flow: string
  moved: number
  skipped: string[]
}

export interface Overview {
  rabbitmqVersion: string
  clusterName: string
  totalMessages: number
  totalConsumers: number
  nodes: { name: string; running: boolean; memUsedMb: number; diskFreeMb: number }[]
}
