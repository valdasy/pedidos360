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
  /** Nodo del clúster donde vive la cola */
  node: string | null
  /** running, o down si su nodo está caído */
  state: string
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

// ---------------- Kafka (Evaluación 3) ----------------

/** ms-report: KPIs calculados desde el tópico pedidos.eventos */
export interface Kpis {
  totalPedidos: number
  pedidosPorEstado: Record<string, number>
  ventasConfirmadas: number
  pedidosConfirmados: number
  ticketPromedio: number
  cancelados: number
  tasaCancelacion: number
  tiempoAceptacionPromedioMin: number | null
  tiempoEntregaPromedioMin: number | null
  eventosProcesados: number
}

export interface TopProducto {
  productoId: number
  nombre: string
  unidades: number
  monto: number
}

export interface VentaDiaria {
  fecha: string
  pedidos: number
  monto: number
}

/** ms-audit: un evento recibido desde Kafka */
export interface AuditEvent {
  id: number
  eventId: string
  tipo: string
  entidad: 'PEDIDO' | 'PRODUCTO'
  entidadId: number | null
  pedidoId: number | null
  descripcion: string
  usuario: string | null
  ocurridoEn: string
  recibidoEn: string
  topico: string
  particion: number
  offset: number
}

export interface AuditResumen {
  totalEventos: number
  porTipo: Record<string, number>
}

/** ms-kafka-admin */
export interface KafkaCluster {
  clusterId: string
  controllerId: number | null
  brokers: { id: number; host: string; port: number; rack: string | null }[]
}

export interface TopicoResumen {
  nombre: string
  particiones: number
  replicas: number
  interno: boolean
}

export interface TopicoDetalle {
  nombre: string
  topicId: string
  interno: boolean
  particiones: {
    id: number
    lider: number | null
    replicas: number[]
    isr: number[]
    offsetInicio: number
    offsetFin: number
  }[]
  configs: Record<string, string>
  mensajes: number
}

export interface GrupoResumen {
  grupoId: string
  estado: string
  tipo: string
}

export interface GrupoDetalle {
  grupoId: string
  estado: string
  coordinador: string | null
  asignador: string
  miembros: { consumerId: string; clientId: string; host: string; particiones: string[] }[]
  offsets: { topico: string; particion: number; offsetConfirmado: number; offsetFin: number; lag: number }[]
  lagTotal: number
}

export interface KafkaMensaje {
  particion: number
  offset: number
  clave: string | null
  valor: string | null
  timestamp: number
  cabeceras: Record<string, string | null>
}

export interface CrearTopicoRequest {
  nombre: string
  particiones: number
  replicas: number
  configs?: Record<string, string>
}
