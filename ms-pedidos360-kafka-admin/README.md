# ms-pedidos360-kafka-admin

Administración de Kafka con `AdminClient` (sin base de datos). Rol: **Admin**.

- Puerto: `8088` · Swagger: http://localhost:8088/swagger-ui.html

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/kafka-admin/cluster` | Brokers y controlador |
| GET | `/api/kafka-admin/topics` | Listar tópicos (`?internos=true` incluye `__consumer_offsets`) |
| GET | `/api/kafka-admin/topics/{nombre}` | Particiones (líder, réplicas, ISR, offsets) y configuración |
| POST | `/api/kafka-admin/topics` | Crear `{nombre, particiones, replicas, configs}` |
| PATCH | `/api/kafka-admin/topics/{nombre}/configs` | Modificar config `{configs: {"retention.ms": "..."}}` (vacío = valor por defecto) |
| POST | `/api/kafka-admin/topics/{nombre}/partitions` | Aumentar particiones `{total}` (reducir → 400: Kafka no lo permite) |
| DELETE | `/api/kafka-admin/topics/{nombre}` | Eliminar (los internos `__*` están protegidos) |
| GET | `/api/kafka-admin/topics/{nombre}/messages?limit=` | Últimos mensajes con cabeceras (para revisar el DLT) |
| POST | `/api/kafka-admin/topics/{nombre}/messages` | Publicar un mensaje de texto (demostrar errores y DLT) |
| GET | `/api/kafka-admin/groups` | Consumer groups |
| GET | `/api/kafka-admin/groups/{id}` | Miembros, particiones asignadas, offsets y LAG |

Errores del broker → HTTP: tópico inexistente 404, ya existe 409, parámetros inválidos 400, Kafka caído 503.
