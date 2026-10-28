# Guía de estudio — Pedidos360

Para preparar la presentación. Cada sección indica **dónde está en el código** para que
puedas mostrarlo si el profesor lo pide.

---

## 1. La idea en 30 segundos

> Pedidos360 es un sistema de pedidos con microservicios Spring Boot. El usuario inicia sesión
> con **Microsoft Entra ID** (IDaaS) y recibe un JWT con su rol. Todas las llamadas pasan por
> **AWS API Gateway** (API Manager), que valida el token antes de llegar a los
> microservicios. Las tareas lentas (correos, tickets de cocina, boletas) se envían como
> **comandos asíncronos por RabbitMQ**, con colas de error (DLQ) y un microservicio que las
> administra. Todo corre en Docker sobre EC2, con Oracle en RDS.

---

## 2. Unidad 2 — Mensajería asíncrona con RabbitMQ

### IL 2.1 Configura un componente de colas

| Concepto | En una frase | Dónde |
|---|---|---|
| **Producer** | Publica mensajes (ms-orders) | `CommandPublisher.java` |
| **Exchange** | Recibe el mensaje y decide a qué colas va según los bindings | `infra/mq/definitions.json` |
| **Direct** | Coincidencia **exacta** de routing key (`email.send`) | `cmd.direct` |
| **Topic** | Patrones: `*` = una palabra, `#` = cero o más (`email.#`) | `cmd.topic` |
| **Binding** | Regla exchange → cola con una key o patrón | definitions.json |
| **Queue durable** | Sobrevive a reinicios del broker | las 6 colas |
| **Mensaje persistente** | `delivery_mode=2`: se guarda en disco | `MessageDeliveryMode.PERSISTENT` |
| **DLX / DLQ** | Exchange y cola donde terminan los mensajes rechazados | `cmd.dead.dlx`, `*.dlq` |
| **Policy** | Configura colas existentes sin recrearlas (aquí: a qué DLX van) | `policy.*.dlx` |

**Pregunta típica:** *¿durable y persistente son lo mismo?* No. *Durable* es la cola (su
definición sobrevive al reinicio); *persistente* es el mensaje (su contenido se guarda en disco).
Se necesitan **ambos** para no perder mensajes.

**Pregunta típica:** *¿por qué `email.#` y no `email.*`?* `email.*` acepta exactamente una
palabra más (`email.send`), pero no `email.send.high`. Con `#` acepta cualquier cantidad.
En cambio, `invoice.*` se dejó como en el caso, y la prueba muestra que `invoice.gen.pdf`
no se enruta (la API responde 422).

### IL 2.2 Implementa una solución asíncrona

**El flujo:** `PATCH /api/orders/4/status {ACEPTADO}` → orders guarda en Oracle → después del
commit publica `EMAIL_SEND` y `KITCHEN_TICKET` → responde al usuario → **más tarde** notify los
consume, envía el correo y genera el ticket.

| Concepto | Explicación | Dónde |
|---|---|---|
| **¿Por qué asíncrono?** | El usuario no espera el correo; si el SMTP está caído, el pedido igual se acepta y el correo sale cuando vuelva | `OrderCommandDispatcher` |
| **¿Y por qué stock es síncrono?** | Para aceptar un pedido **necesito saber ya** si hay stock | `CatalogClient` |
| **Envelope** | type, eventId, timestamp, traceId, correlationId (lo pide el caso) | `CommandEnvelope.java` |
| **Publisher confirms** | El broker confirma que guardó el mensaje; sin esto, "publicar" solo significa "salió de mi app" | `CommandPublisher.waitForConfirm` |
| **mandatory / returns** | Si ninguna cola coincide, el broker devuelve el mensaje → 422 en vez de perderlo en silencio | idem |
| **ACK manual** | El consumidor confirma **después** de procesar. Si se cae a la mitad, el mensaje vuelve a la cola | `CommandListener.java` |
| **NACK con requeue** | Error transitorio (SMTP caído): reintentar | idem |
| **REJECT sin requeue** | Error permanente (correo sin @): reintentar no sirve → DLQ | idem |
| **Idempotencia** | El mismo `eventId` no se procesa dos veces (no hay correos duplicados) | `ProcessedEventStore.java` |
| **AFTER_COMMIT** | Se publica solo si el pedido quedó guardado | `@TransactionalEventListener` |
| **Prefetch** | Máximo de mensajes sin confirmar por consumidor (10) | `application.properties` de notify |

**Pregunta típica:** *¿qué pasa si notify está apagado?* Los mensajes se acumulan en *Ready*;
orders sigue respondiendo; al levantar notify los procesa todos. (¡Demuéstralo!)

**Pregunta típica:** *¿y si RabbitMQ cae justo después de guardar el pedido?* El comando se
pierde (queda en el log). La solución profesional es el patrón **Transactional Outbox**: guardar
el comando en una tabla en la misma transacción y publicarlo desde ahí con reintentos.

**Pregunta típica:** *¿la idempotencia funciona con 2 instancias de notify?* **No**, porque el
registro es en memoria de cada instancia (lo comprobamos: llegaron 2 correos). Para escalar se
necesita un registro compartido (Redis o tabla con clave única).

### IL 2.3 Administra las colas

| Caso de uso | Cómo | Dónde |
|---|---|---|
| Ver estado (Ready/Unacked/Consumers) | API HTTP de Management | `GET /api/mq-admin/flows` |
| Saber por qué falló un mensaje | Cabecera `x-death` (reason, exchange y routing key originales) | `GET .../dlq/messages` |
| Reintentar fallidos | Reproceso DLQ → exchange original **sin pérdida** | `DlqReprocessor.java` |
| Evitar ciclos infinitos | Máximo 3 reprocesos (`x-reprocess-count`) | idem |
| Descartar | Purgar la DLQ | `DELETE .../dlq/messages` |
| Alertas | Sin consumidores (CRITICAL), acumulación, DLQ con mensajes | `QueueAdminService.alerts` |
| Clúster | 2 nodos, mismo `erlang cookie` | `infra/mq-cluster` |

**Reproceso sin pérdida, en 4 pasos:** `basicGet` sin ACK → publicar en el origen → esperar
confirmación → **recién entonces** ACK en la DLQ. Si algo falla en medio, el mensaje vuelve
a la DLQ. Es entrega **al menos una vez** (*at-least-once*) + idempotencia.

**Pregunta típica:** *¿un clúster de 2 nodos es alta disponibilidad?* No por sí solo. Comparte
la configuración, pero una cola classic vive en un nodo: si cae, esa cola queda `down` (lo
probamos). La alta disponibilidad real son **quorum queues** con **3 nodos** (necesitan mayoría).

---

## 3. Unidad 1 — API Manager e IDaaS

### IL 1.2 Identity as a Service (Entra ID)

| Concepto | Explicación |
|---|---|
| **IDaaS** | Delegamos login, claves, MFA y recuperación a Microsoft. No guardamos contraseñas |
| **App registration** | "Ficha" de una app en Entra: client ID, redirect URI, permisos |
| **Dos registros** | `pedidos360-spa` (frontend) y `pedidos360-api` (backend). El token es **para la API** |
| **Scope** `access_as_user` | Permiso que la SPA pide para llamar a la API en nombre del usuario |
| **App roles** | Admin, Operador, Cliente; llegan en el claim `roles` del access token |
| **MSAL** | Librería que hace el login (Authorization Code + **PKCE**) y renueva tokens |
| **PKCE** | Protege el código de autorización en apps públicas (sin client secret) |
| **Sin client secret en la SPA** | Todo lo que está en el navegador es visible |
| **Redirect URI** | A dónde vuelve el usuario tras el login; debe coincidir exacto y ser SPA |

**JWT (JSON Web Token):** `header.payload.firma`. Claims importantes:

| Claim | Significado | Quién lo valida |
|---|---|---|
| `iss` | Quién lo emitió (nuestro tenant) | Gateway y Spring |
| `aud` | Para quién es (nuestra API) | Gateway y Spring |
| `exp` | Vencimiento (~1 h) | Gateway y Spring |
| `roles` | Rol del usuario | Spring |
| `preferred_username` | Email/UPN del usuario | Spring (dueño del pedido) |
| firma | Garantiza que nadie lo modificó (clave pública de Entra, JWKS) | Gateway y Spring |

**Autenticación vs autorización:** *¿quién eres?* (Entra) vs *¿qué puedes hacer?* (roles en Spring).

### IL 1.1 API Manager (AWS API Gateway)

| Funciones | En Pedidos360 |
|---|---|
| Punto único de entrada | Una URL HTTPS para 3 microservicios |
| Autenticar | JWT authorizer (issuer + audience de Entra) → 401 |
| Filtrar / enrutar | Rutas por método y prefijo → integración del microservicio |
| Escalar / proteger | Throttling 50 req/s (ráfaga 100) → 429 |
| Securizar | HTTPS automático, CORS restringido al frontend |

**Pregunta típica:** *¿si el gateway valida el token, por qué Spring lo valida otra vez?*
**Defensa en profundidad**: los puertos de la EC2 son alcanzables; además, el gateway no
conoce las reglas de negocio (un Cliente solo cancela **sus** pedidos **no aceptados**).

### IL 1.3 Integración full stack en la nube

**Recorrido de una petición (dibújalo en la pizarra):**

1. React → `loginRedirect` → Entra ID → vuelve con un código → MSAL lo canjea por tokens.
2. React → `acquireTokenSilent` → `Authorization: Bearer <token>`.
3. API Gateway: CORS → throttling → JWT authorizer → ruta.
4. EC2: orders (Spring Security valida otra vez + rol) → Oracle (RDS).
5. Si aceptó: orders → catalog (REST síncrono, **reenviando el token**) → stock.
6. orders → RabbitMQ (comandos) → notify → Mailpit.

**Matriz de roles:**

| Acción | Admin | Operador | Cliente |
|---|---|---|---|
| Ver pedidos | todos | todos | **solo los suyos** |
| Crear pedido | — | ✓ | ✓ (a su nombre) |
| Aceptar / preparar / despachar / entregar | ✓ | ✓ | — |
| Cancelar | ✓ | ✓ | solo los suyos en CREADO |
| Ver catálogo | ✓ | ✓ | ✓ (para comprar) |
| Editar catálogo | ✓ | — | — |
| Administrar colas | ✓ | — | — |

---

## 4. Dominio y datos

- **Máquina de estados** (`OrderStatus.java`): CREADO → ACEPTADO → EN_PREPARACION → DESPACHADO →
  ENTREGADO, y CANCELADO desde los tres primeros. "No se puede despachar sin aceptar" se cumple
  porque a DESPACHADO solo se llega desde EN_PREPARACION.
- **Stock al aceptar**, todo o nada, con `SELECT ... FOR UPDATE` (sin sobreventa) y reservas
  idempotentes. Cancelar devuelve el stock.
- **Database per service**: un esquema Oracle por microservicio; orders no lee tablas de catalog.
- **Flyway**: scripts SQL versionados (`V1__...`) → la base de RDS queda igual a la local.
- **Lead time**: `entregado_en - creado_en` (se ve en el dashboard del Admin).

---

## 5. Guion de demo (10 minutos)

Ten abiertas pestañas: app, RabbitMQ UI, Mailpit, una terminal.

1. **Login** con `cliente@...` → mostrar el chip "Cliente" y el menú reducido.
   Botón *Copiar token* → jwt.ms → mostrar `iss`, `aud` y `roles`. *(IL 1.2)*
2. **Crear pedido** (2 cappuccinos). Mostrar en Mailpit "Recibimos tu pedido". *(IL 2.2)*
3. **API Gateway:** en la terminal, `curl -i <API>/api/orders` → **401** sin token. *(IL 1.1)*
4. Login con `operador@...` → **Aceptar** el pedido → stock baja en Catálogo, ticket en el log
   de notify, correo en Mailpit. Intentar **despachar** otro pedido sin aceptar: no aparece el
   botón; por API → 409.
5. **Asincronía:** detener notify (`docker compose stop notify`) → avanzar el pedido → en
   RabbitMQ `Ready` sube → levantar notify → se procesan. *(IL 2.2)*
6. **DLQ:** detener Mailpit, avanzar un pedido → el mensaje termina en la DLQ tras 1 reintento →
   login `admin@...` → Colas RabbitMQ → alerta → **Ver DLQ** (`x-death`) → levantar Mailpit →
   **Reprocesar** → llega el correo. *(IL 2.3)*
7. Cliente intenta entrar a `/admin/mq` → "Acceso denegado"; por API → 403.

---

## 6. Comandos que conviene saber de memoria

```bash
docker compose ps
```
```bash
docker compose logs -f notify
```
```bash
docker exec pedidos360-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```
```bash
docker compose run --rm topology-init
```
```bash
bash scripts/probar-pedidos.sh
```

## 7. Decisiones que puedes defender

| Decisión | Por qué |
|---|---|
| Monorepo | Microservicios = despliegue independiente, no repos separados. Cada `ms-*` tiene su pom y Dockerfile |
| React en vez de Angular | Tutorial del curso; mismo MSAL (`msal-browser`) |
| Mailpit | SMTP real sin enviar correos a personas; funciona sin internet |
| Políticas DLX en vez de argumentos | Se cambian sin recrear colas |
| Topología en `definitions.json` | Infraestructura como código: se recrea igual en AWS |
| Seguridad desactivable (`SECURITY_ENABLED`) | Desarrollar sin depender de Azure; en AWS siempre `true` |
| Rutas por método en API Gateway | `ANY` captura el preflight `OPTIONS` y CORS falla con 401 |
