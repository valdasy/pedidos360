# ms-pedidos360-mq-admin

Microservicio administrador de RabbitMQ (IL 2.3). Permite monitorear las colas,
revisar la DLQ, reprocesar mensajes fallidos y purgar.

- Puerto: `8085`
- Swagger: http://localhost:8085/swagger-ui.html

## Ejecutar

Requiere `infra/mq` levantado. Credenciales desde `../infra/mq/.env`.

```bash
./mvnw spring-boot:run
```

## Endpoints

| Método | Ruta | Para qué |
|---|---|---|
| GET | `/api/mq-admin/overview` | Versión, total de mensajes, consumidores y **nodos del clúster** |
| GET | `/api/mq-admin/flows` | Ready / Unacked / Consumers de las 3 colas y sus DLQ |
| GET | `/api/mq-admin/flows/{flow}` | Lo mismo para un flujo (`email`, `kitchen`, `invoice`) |
| GET | `/api/mq-admin/flows/{flow}/dlq/messages?count=10` | Ver mensajes de la DLQ **sin retirarlos**, con motivo y origen (`x-death`) |
| POST | `/api/mq-admin/flows/{flow}/dlq/reprocess?count=10` | Devolver mensajes de la DLQ a su exchange y routing key originales |
| DELETE | `/api/mq-admin/flows/{flow}/dlq/messages` | Purgar la DLQ (irreversible) |
| GET | `/api/mq-admin/alerts` | Alertas activas |

## Cómo se conecta a RabbitMQ

| Canal | Puerto | Uso |
|---|---|---|
| API HTTP de Management | 15672 | Leer contadores, ver mensajes, purgar (`RabbitManagementClient`) |
| AMQP | 5672 | Reprocesar con garantía de no pérdida (`DlqReprocessor`) |

## Reproceso sin pérdida (`DlqReprocessor`)

1. `basicGet(dlq, autoAck=false)`: toma el mensaje **sin confirmarlo**.
2. Lee `x-death` para saber su exchange y routing key **originales**.
3. Lo publica ahí y espera el **publisher confirm**.
4. Recién entonces hace `basicAck` en la DLQ.

Si algo falla entre medio, el canal se cierra y RabbitMQ devuelve el mensaje a la DLQ.
El peor caso es un duplicado, que la idempotencia de ms-notify (`eventId`) neutraliza.

Protecciones:
- Cabecera `x-reprocess-count`: máximo 3 reprocesos por mensaje. Después queda en la
  DLQ para **revisión manual** (evita el ciclo infinito DLQ → cola → DLQ).
- Cada llamada procesa solo los mensajes que había al comenzar. Si uno vuelve a fallar
  durante la misma llamada, no se reprocesa de nuevo de inmediato.
- Mensajes sin `x-death` (origen desconocido) no se reprocesan.

## Alertas

Se revisan cada 30 s y se escriben en el log (`[ALERTA ...]`). También en `GET /alerts`.

| Regla | Nivel |
|---|---|
| Cola principal sin consumidores | CRITICAL |
| Cola principal con ≥ 100 mensajes Ready | WARNING |
| DLQ con mensajes | WARNING (≥ 10: CRITICAL) |

Umbrales configurables en `application.properties` (`pedidos360.mq.alerts.*`).

## Notas

- Las estadísticas de la API de Management se refrescan cada ~5 s.
- Spring Boot 4: el `RestClient.Builder` automático está en `spring-boot-starter-restclient`;
  aquí se usa `RestClient.builder()` directamente.
