# ms-pedidos360-notify

Consumidor RabbitMQ. No expone HTTP público. Escucha tres colas:

| Cola | Acción |
|---|---|
| `q.cmd.email` | Envía el correo por SMTP (Mailpit en desarrollo: http://localhost:8025) |
| `q.cmd.kitchen` | Imprime el ticket de preparación (log) |
| `q.cmd.invoice` | Genera la boleta o factura (log) |

## Ejecutar

Requiere `infra/mq` levantado (RabbitMQ + Mailpit). Credenciales desde `../infra/mq/.env`.

```bash
./mvnw spring-boot:run
```

## ACK manual (`CommandListener`)

`acknowledge-mode=manual`: el código decide qué hacer con cada mensaje.

| Situación | Acción | Resultado |
|---|---|---|
| Procesado OK | `basicAck` | Sale de la cola |
| `eventId` ya procesado | `basicAck` sin procesar | Idempotencia: no hay correo duplicado |
| JSON ilegible o sin envelope | `basicReject(requeue=false)` | DLQ |
| Datos inválidos (`InvalidCommandException`) | `basicReject(requeue=false)` | DLQ (reintentar no sirve) |
| Error transitorio (SMTP caído), 1.ª vez | `basicNack(requeue=true)` | Se reintenta una vez |
| Error transitorio, ya reintentado (`redelivered`) | `basicReject(requeue=false)` | DLQ (lo administra ms-mq-admin) |

## Notas

- Recibe el `Message` crudo y lo convierte con `ObjectMapper` a su propio DTO: los
  servicios comparten el **formato JSON**, no las clases Java (evita el error de
  conversión descrito en la guía Parte 2).
- Spring Boot 4 sin starter web no incluye Jackson: se agrega `spring-boot-starter-jackson`.
- La idempotencia es en memoria (se pierde al reiniciar). En producción: Redis o tabla con clave única.
- SMTP con timeouts de 5 s: sin ellos, un SMTP colgado bloquea al consumidor.
