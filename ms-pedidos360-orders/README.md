# ms-pedidos360-orders

Microservicio de pedidos: CRUD, máquina de estados, coordinación de stock (con ms-catalog)
y publicación de comandos en RabbitMQ. Base de datos: esquema `pedidos_orders` (Oracle).

- Puerto: `8081`
- Swagger: http://localhost:8081/swagger-ui.html

## Ejecutar

Requiere `infra/mq`, `infra/db` y ms-catalog. Claves desde `../infra/mq/.env` y `../infra/db/.env`.

```bash
./mvnw spring-boot:run
```

## Endpoints de pedidos

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/orders?estado=&email=` | Listar (filtros opcionales) |
| GET | `/api/orders/{id}` | Obtener (incluye `siguientesEstados` permitidos) |
| GET | `/api/orders/{id}/history` | Historial de estados |
| POST | `/api/orders` | Crear. Nombre y precio de cada ítem se toman de ms-catalog |
| PUT | `/api/orders/{id}` | Editar (solo en CREADO) |
| DELETE | `/api/orders/{id}` | Eliminar (solo en CREADO; si no, se cancela) |
| PATCH | `/api/orders/{id}/status` | Cambiar estado: `{"estado":"ACEPTADO"}` |

## Máquina de estados

```
CREADO -> ACEPTADO -> EN_PREPARACION -> DESPACHADO -> ENTREGADO
   \          \              \
    +----------+--------------+--> CANCELADO
```

| Transición | Efecto |
|---|---|
| a ACEPTADO | Descuenta stock en ms-catalog (síncrono). Si falta stock: 409 y el pedido sigue CREADO |
| a CANCELADO desde ACEPTADO / EN_PREPARACION | Devuelve el stock en ms-catalog |
| transición no permitida | 409 indicando a qué estados se puede ir |

"No se puede despachar sin aceptar": a DESPACHADO solo se llega desde EN_PREPARACION.

## Comandos RabbitMQ (`OrderCommandDispatcher`)

Se publican **después del commit** (`@TransactionalEventListener(AFTER_COMMIT)`):

| Estado | Comandos |
|---|---|
| Todos | `EMAIL_SEND` → q.cmd.email |
| ACEPTADO | + `KITCHEN_TICKET` → q.cmd.kitchen |
| ENTREGADO | + `INVOICE_GEN` → q.cmd.invoice |

Limitación conocida: si RabbitMQ falla justo después del commit, el comando se pierde
(queda en el log). Mejora: patrón **Transactional Outbox**.

## ¿Síncrono o asíncrono?

| Interacción | Tipo | Por qué |
|---|---|---|
| orders → catalog (stock) | **Síncrono** (REST) | Se necesita la respuesta para decidir si se acepta |
| orders → notify (correo, ticket, boleta) | **Asíncrono** (RabbitMQ) | Puede hacerse después; el usuario no espera |

## Endpoints de prueba de mensajería (Etapa 2)

`POST /api/orders/commands/{email|kitchen|invoice}`: publican comandos directamente
(`?priority=high` usa `cmd.topic`; cabecera `X-Event-Id` para probar idempotencia).
Responden 202 si el broker confirmó, 422 si no se enrutó, 503 si el broker no confirma.
