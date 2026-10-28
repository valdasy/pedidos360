# ms-pedidos360-catalog

Catálogo de productos, precios y stock. Base de datos: esquema `pedidos_catalog` (Oracle).

- Puerto: `8082`
- Swagger: http://localhost:8082/swagger-ui.html

## Ejecutar

Requiere `infra/db` levantado. Claves desde `../infra/db/.env`.

```bash
./mvnw spring-boot:run
```

Al arrancar, Flyway crea las tablas y carga 6 productos de ejemplo.

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/catalog/products` | Listar |
| GET | `/api/catalog/products/{id}` | Obtener |
| POST | `/api/catalog/products` | Crear (409 si el SKU ya existe) |
| PUT | `/api/catalog/products/{id}` | Actualizar (precio, stock, etc.) |
| DELETE | `/api/catalog/products/{id}` | Eliminar |
| POST | `/api/catalog/stock/reservations` | **Uso de ms-orders**: descontar stock de un pedido |
| GET | `/api/catalog/stock/reservations/{pedidoId}` | Ver reserva |
| DELETE | `/api/catalog/stock/reservations/{pedidoId}` | **Uso de ms-orders**: devolver stock (cancelación) |

## Reglas del stock

- **Todo o nada:** si falta stock de un producto, no se descuenta ninguno (una sola
  transacción). Responde 409 con el detalle (`solicitado` / `disponible`).
- **Sin sobreventa:** `SELECT ... FOR UPDATE` bloquea las filas de los productos mientras
  se descuenta, y se bloquean siempre en el mismo orden (por id) para evitar deadlocks.
  Además, la tabla tiene `CHECK (stock >= 0)`.
- **Idempotente:** una reserva por pedido. Reservar dos veces el mismo pedido no descuenta
  dos veces; liberar dos veces no devuelve dos veces.
- Bloqueo optimista (`@Version`) en la edición de productos.

## Nota técnica

Hibernate 7 mapea `Instant` a `TIMESTAMP WITH TIME ZONE`. Con columnas `TIMESTAMP`,
Oracle falla con `ORA-18716`; se configura `preferred_instant_jdbc_type=TIMESTAMP` y UTC.
