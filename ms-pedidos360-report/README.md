# ms-pedidos360-report

KPIs y analítica. **Consumidor Kafka** (grupo `pedidos360-report`) de `pedidos.eventos`. Construye su
propia proyección de pedidos en Oracle (esquema `pedidos_report`) **sin consultar la base de orders**.

- Puerto: `8087` · Swagger: http://localhost:8087/swagger-ui.html · Roles: **Admin, Operador**
- Idempotencia: el `eventId` se guarda en `rep_evento_procesado` en la misma transacción que actualiza los KPIs

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/report/kpis` | Pedidos por estado, ventas confirmadas, ticket promedio, tasa de cancelación, tiempos de aceptación y entrega |
| GET | `/api/report/top-productos?limit=5` | Productos más vendidos (pedidos confirmados) |
| GET | `/api/report/ventas-diarias?dias=14` | Ventas confirmadas por día |

Requiere `infra/kafka`, `infra/db` (usuario `pedidos_report`). `./mvnw spring-boot:run`
