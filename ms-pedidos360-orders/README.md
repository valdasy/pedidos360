# ms-pedidos360-orders

Microservicio de pedidos. En la Etapa 2 actúa como **productor RabbitMQ**: publica
comandos en `cmd.direct` / `cmd.topic`. En la Etapa 4 se agrega el CRUD de pedidos y
la máquina de estados, que publicará estos mismos comandos automáticamente.

- Puerto: `8081`
- Swagger: http://localhost:8081/swagger-ui.html

## Ejecutar

Requiere `infra/mq` levantado. Las credenciales se leen de `../infra/mq/.env`.

```bash
./mvnw spring-boot:run
```

## Endpoints de prueba (Etapa 2)

| Método | Ruta | Publica en |
|---|---|---|
| POST | `/api/orders/commands/email` | `cmd.direct` [`email.send`] |
| POST | `/api/orders/commands/kitchen` | `cmd.direct` [`kitchen.ticket`] |
| POST | `/api/orders/commands/invoice` | `cmd.direct` [`invoice.gen`] |

- `?priority=high` publica en `cmd.topic` con `<routing key>.high`.
- Cabecera opcional `X-Event-Id: <uuid>` para reenviar un mismo evento (prueba de idempotencia).

| Respuesta | Significado |
|---|---|
| 202 Accepted | El broker **confirmó** el mensaje y quedó en una cola. El trabajo se hará después |
| 422 | Mensaje no enrutado: ninguna cola coincide con la routing key |
| 503 | Broker caído o sin confirmación |

## Cómo funciona la publicación (`CommandPublisher`)

1. Envuelve el payload en un `CommandEnvelope` (type, eventId, timestamp, traceId, correlationId).
2. Publica con `RabbitTemplate.convertAndSend` como JSON persistente.
3. Espera el **publisher confirm** del broker (máx. 5 s).
4. Con `mandatory=true`, si ningún binding coincide el broker **devuelve** el mensaje
   y la API responde 422 en vez de perderlo en silencio.
