# ms-pedidos360-audit

Auditoría / timeline. **Consumidor Kafka** (grupo `pedidos360-audit`) de `pedidos.eventos` e
`inventario.eventos`. Guarda cada evento en Oracle (esquema `pedidos_audit`) con su tópico, partición y offset.

- Puerto: `8086` · Swagger: http://localhost:8086/swagger-ui.html · Rol: **Admin**
- Errores: 3 reintentos con backoff y luego `*.DLT` (`config/KafkaErrorConfig`)
- Idempotencia: `event_id` UNIQUE

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/audit/events?entidad=&entidadId=&tipo=&usuario=&limit=` | Buscar eventos (recientes primero) |
| GET | `/api/audit/pedidos/{id}/timeline` | Timeline de un pedido (pedido + stock) |
| GET | `/api/audit/resumen` | Cantidad de eventos por tipo |

Requiere `infra/kafka`, `infra/db` (usuario `pedidos_audit`: `infra/db/crear-usuarios-kafka.sh`). `./mvnw spring-boot:run`
