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
> administra. Además, cada cambio de un pedido o del stock se publica como **evento en Kafka**,
> que leen la auditoría y los reportes (Evaluación 3). Todo corre en Docker sobre EC2, con Oracle
> en una EC2 (el Learner Lab bloquea RDS Oracle).

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

**Pregunta típica:** *¿cómo agregaron la nota de crédito?* Al cancelar, orders publica
`CREDIT_NOTE_GEN` en **cmd.topic** con routing key `invoice.credit`. El binding `invoice.*`
que ya existía la lleva a `q.cmd.invoice`: **no hubo que tocar RabbitMQ**. Notify distingue
boleta de nota de crédito por la propiedad AMQP `type`, genera `NC-000123` y la envía por
correo. Esa es la ventaja de un exchange topic: variantes de un flujo sin nuevas colas.

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
| Clúster | 2 nodos, colas repartidas, servicios con failover | `infra/mq-cluster`, `scripts/probar-cluster.sh` |

**Reproceso sin pérdida, en 4 pasos:** `basicGet` sin ACK → publicar en el origen → esperar
confirmación → **recién entonces** ACK en la DLQ. Si algo falla en medio, el mensaje vuelve
a la DLQ. Es entrega **al menos una vez** (*at-least-once*) + idempotencia.

**Pregunta típica:** *¿un clúster de 2 nodos es alta disponibilidad?* No por completo. Todo
Pedidos360 usa el clúster: las colas están **repartidas** (email y kitchen en el nodo 1, invoice
en el 2) y los servicios se conectan a **ambos nodos** (`spring.rabbitmq.addresses`). Probado
(`probar-cluster.sh`): si cae un nodo, los servicios se reconectan al otro, mq-admin sigue
respondiendo y alerta, y los flujos cuyas colas viven en el nodo vivo siguen funcionando. Pero
las colas del nodo caído quedan **no disponibles** hasta que vuelva: una cola classic vive en un
solo nodo. La alta disponibilidad real de las colas son **quorum queues** con **3 nodos**
(necesitan mayoría).

**Pregunta típica:** *¿qué problemas encontraron con el clúster?* (1) Spring detenía para siempre
el listener de una cola no disponible (`missing-queues-fatal=false` lo corrige); (2) si fallaba
el primer comando de un pedido, no se intentaban los demás (ahora se publican por separado);
(3) gracias a los *publisher confirms*, orders sabe cuándo un comando no llegó y lo registra.

---

## 3. Unidad 1 — API Manager e IDaaS

### IL 1.2 Identity as a Service (Entra ID)

| Concepto | Explicación |
|---|---|
| **IDaaS** | Delegamos login, claves, MFA y recuperación a Microsoft. No guardamos contraseñas |
| **App registration** | "Ficha" de una app en Entra: client ID, redirect URI, permisos |
| **Dos registros** | *Pedidos360 Frontend* (SPA) y *Pedidos360 API* (backend). El token es **para la API** |
| **Scope** `user_impersonation` | Permiso que la SPA pide para llamar a la API en nombre del usuario |
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
| Punto único de entrada | Una URL HTTPS para 6 microservicios (orders, catalog, mq-admin, audit, report, kafka-admin) |
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
4. EC2: orders (Spring Security valida otra vez + rol) → Oracle (EC2 db).
5. Si aceptó: orders → catalog (REST síncrono, **reenviando el token**) → stock.
6. orders → RabbitMQ (comandos) → notify → Mailpit.
7. orders → Kafka `pedidos.eventos` → ms-audit (timeline) y ms-report (KPIs).

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
| Ver reportes (KPIs) | ✓ | ✓ | — |
| Auditoría y administrar Kafka | ✓ | — | — |

---

## 4. Evaluación 3 — Apache Kafka

### La idea

**RabbitMQ manda comandos; Kafka publica hechos.** RabbitMQ dice "envía este correo" a **un**
trabajador y el mensaje se borra al confirmarlo. Kafka registra "el pedido 7 pasó a ACEPTADO" en un
**log** que guarda los eventos 7 días y que leen **varios** consumidores, cada uno a su ritmo.

```
orders  ─► pedidos.eventos    (clave pedidoId)  ─┬─► grupo pedidos360-audit  ─► ms-audit  (timeline, Admin)
catalog ─► inventario.eventos (clave productoId) ─┘
           pedidos.eventos  ──────────────────────► grupo pedidos360-report ─► ms-report (KPIs)
           *.DLT = eventos que fallaron             ms-kafka-admin = administrar tópicos y grupos
```

| Concepto | En una frase | En Pedidos360 |
|---|---|---|
| **Broker** | Servidor de Kafka que guarda las particiones | `kafka1` (:9092) y `kafka2` (:9093) |
| **ZooKeeper** | Guarda los metadatos del clúster y elige el controlador | `infra/kafka` (Confluent 7.9; en Kafka 4 lo reemplaza KRaft) |
| **Tópico** | Canal con nombre donde se publican eventos | `pedidos.eventos`, `inventario.eventos` |
| **Partición** | División del tópico; el orden se garantiza **dentro** de cada una | 3 por tópico |
| **Réplica** | Copia de la partición en otro broker | factor 2: cada partición está en ambos brokers |
| **Líder / ISR** | Líder = réplica que atiende; ISR = réplicas al día | Kafka UI → Topics |
| **Offset** | Posición de un evento dentro de su partición | se ve en la auditoría: `pedidos.eventos-2@4` |
| **Clave** | Decide la partición: `hash(clave) % particiones` | pedidoId / productoId |
| **Consumer group** | Consumidores que se reparten las particiones; cada grupo lee todo | `pedidos360-audit`, `pedidos360-report` |
| **LAG** | Eventos que el grupo todavía no procesa | Kafka UI → Consumers (debe ser 0) |
| **Retención** | Cuánto tiempo se guardan los eventos | 7 días (DLT 30) |
| **DLT** | Tópico con los eventos que fallaron | `pedidos.eventos.DLT` |

### Productores (orders y catalog)

| Configuración | Para qué | Dónde |
|---|---|---|
| `AFTER_COMMIT` | Solo se publica lo que quedó guardado en Oracle | `PedidoEventPublisher`, `InventarioEventPublisher` |
| `acks=all` | El broker responde cuando el evento está en todas las réplicas al día | `application.properties` |
| `enable.idempotence=true` | Un reintento de red no duplica el evento | idem |
| `max.block.ms=5000` | Si Kafka no responde, la petición HTTP no se cuelga | idem |
| DTO tipado + `JacksonJsonSerializer` | Record `PedidoEvento` → JSON, sin cabeceras de tipo Java | `events/PedidoEvento.java` |
| `eventId` (UUID) y `version` | Idempotencia en el consumidor y evolución del contrato | idem |

### Consumidores (audit y report)

| Configuración | Para qué | Dónde |
|---|---|---|
| `@KafkaListener` con `groupId` | Cada servicio en su propio grupo → ambos reciben todo | `AuditListener`, `ReporteListener` |
| `ErrorHandlingDeserializer` | Un JSON roto no bloquea al consumidor: va al DLT | `application.properties` |
| `spring.json.value.default.type` | A qué DTO convertir el JSON (cada consumidor tiene su copia) | `@KafkaListener(properties=...)` |
| `enable-auto-commit=false` + `ack-mode=record` | El offset se confirma **después** de procesar cada evento | `application.properties` |
| `DefaultErrorHandler` | 3 reintentos (1, 2 y 4 s); luego DLT | `KafkaErrorConfig.java` |
| `InvalidEventException` | Evento inválido → DLT **sin** reintentos | `validar()` de cada DTO |
| Idempotencia | audit: `event_id` UNIQUE · report: `eventId` guardado en la misma transacción | `AuditService`, `ReporteService` |

### Administrador (`ms-kafka-admin`, AdminClient)

Crear, listar, describir (líder, réplicas, ISR, offsets), modificar `retention.ms`, **aumentar**
particiones (reducir → 400), eliminar, ver mensajes (para revisar el DLT) y ver consumer groups
con su LAG. Protegido con rol Admin.

### Preguntas típicas

**¿Por qué Kafka si ya tenían RabbitMQ?** Son para cosas distintas. Un correo lo debe enviar
**un** trabajador y luego desaparece (RabbitMQ). Un evento de negocio interesa a **varios**
(auditoría, reportes, y mañana otros) y conviene **guardarlo** para releerlo (Kafka).

**¿Cómo garantizan el orden?** Por partición. La clave es el pedidoId, así que todos los eventos de
un pedido caen en la misma partición y llegan en orden. Entre pedidos distintos no hay orden global,
y no hace falta. Se ve en la auditoría: los 5 eventos de un pedido tienen la misma partición.

**¿Qué pasa si cae un broker?** Cada partición tiene réplica en el otro. Probado: con `kafka2`
detenido, el broker 1 asumió como líder de las 3 particiones (ISR = [1]) y el pedido igual llegó a
auditoría. Al volver, `kafka2` se resincronizó solo. Funciona porque `min.insync.replicas=1`.

**¿Qué pasa si cae ms-report?** Los eventos se acumulan en Kafka (sube el LAG). Al volver, sigue
desde su último offset confirmado. No se pierde nada, porque los eventos se guardan 7 días.

**¿Y si un evento llega dos veces?** Kafka entrega "al menos una vez". Cada evento trae un `eventId`;
audit tiene `event_id` UNIQUE y report guarda el `eventId` en la **misma transacción** que suma los
KPIs. Probado: se republicó un evento y no se contó dos veces.

**¿Qué pasa con un evento malo?** Si el JSON está roto o falta un campo obligatorio, va directo al
DLT sin reintentos, porque fallaría igual. Si el error es transitorio (Oracle no responde), se
reintenta 3 veces. Probado: con Oracle congelado fallaron 2 intentos y el 3.º funcionó. En ambos casos
el consumidor **sigue** con los demás eventos (LAG 0).

**¿Por qué un evento malo aparece 2 veces en el DLT?** Porque lo leen 2 grupos (audit y report) y
cada uno maneja sus errores. La cabecera `kafka_dlt-original-consumer-group` dice cuál.

**¿DLQ y DLT son lo mismo?** La idea sí. La diferencia: en RabbitMQ el mensaje **sale** de la cola;
en Kafka el original **sigue** en el tópico y el DLT es una copia para revisar o reprocesar.

**¿Cómo se reparten las particiones?** Dentro de un grupo, cada partición la lee un solo
consumidor. Probado con 2 instancias de ms-audit: una recibió las particiones 0 y 1, la otra la 2.
Con 3 particiones, un 4.º consumidor quedaría sin trabajo.

**¿Por qué no se pueden reducir particiones?** Habría que mover o borrar eventos y se rompería el
orden por clave. Kafka solo permite aumentarlas; ms-kafka-admin responde 400 si se intenta.

**¿Por qué los consumidores tienen su propia copia del DTO?** Para no acoplar los microservicios
con una librería compartida. Por eso el productor no manda el nombre de la clase Java.

**¿ms-report consulta la base de orders?** No: construye su propia tabla (`rep_pedido`) solo con
eventos (*database per service*). Si se borrara, se reconstruye releyendo el tópico desde el inicio.

**¿Y si Kafka cae justo después de guardar el pedido?** El evento se pierde (queda en el log de
orders). Igual que con RabbitMQ, la solución es el **Transactional Outbox**.

### En AWS

4.ª EC2 `ec2-kafka` (t3.medium) con `infra/kafka`. Los brokers se anuncian con la **IP privada**
(`KAFKA_ADVERTISED_HOST`), porque si anunciaran `kafka1` la EC2 apps no podría resolver ese nombre.
Security Group: 9092-9093 solo desde `pedidos360-apps` y 8090 (Kafka UI) solo desde tu IP.
ZooKeeper no se abre.

---

## 5. Dominio y datos

- **Máquina de estados** (`OrderStatus.java`): CREADO → ACEPTADO → EN_PREPARACION → DESPACHADO →
  ENTREGADO, y CANCELADO desde los tres primeros. "No se puede despachar sin aceptar" se cumple
  porque a DESPACHADO solo se llega desde EN_PREPARACION.
- **Stock al aceptar**, todo o nada, con `SELECT ... FOR UPDATE` (sin sobreventa) y reservas
  idempotentes. Cancelar devuelve el stock.
- **Database per service**: un esquema Oracle por microservicio; orders no lee tablas de catalog.
- **Flyway**: scripts SQL versionados (`V1__...`) → la base de AWS queda igual a la local.
- **Lead time**: `entregado_en - creado_en`. ms-report lo calcula con los eventos de Kafka (pantalla Reportes).

---

## 6. Guion de demo (12 minutos)

Ten abiertas pestañas: app, RabbitMQ UI (nodo 1 y nodo 2), Mailpit, Kafka UI, una terminal.

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
7. **Clúster (Parte 3):** en Colas RabbitMQ se ve en qué nodo vive cada cola →
   `docker stop pedidos360-rabbit2` → alerta "Nodo caído" y q.cmd.invoice no disponible →
   cancelar un pedido: el correo llega, la nota de crédito no → `docker start pedidos360-rabbit2`.
8. Cliente intenta entrar a `/admin/mq` → "Acceso denegado"; por API → 403.
9. **Kafka (Evaluación 3):** con el pedido ya entregado, abrir **Reportes** (los KPIs cambiaron solos) →
   como Admin, **Auditoría** → buscar el pedido: timeline con `tópico-partición@offset`, todos en la
   misma partición → **Kafka UI**: 2 brokers, tópicos con 3 particiones y réplica 2, grupos con LAG 0.
10. **DLT:** en Swagger de kafka-admin, `POST /topics/pedidos.eventos/messages` con
   `{"clave":"x","valor":"no es json"}` → pantalla **Kafka** → `pedidos.eventos.DLT` → Mensajes:
   aparece con el motivo del error, y el LAG sigue en 0.

---

## 7. Comandos que conviene saber de memoria

```bash
docker compose ps
```
```bash
docker compose logs -f notify
```
```bash
docker exec pedidos360-rabbit1 rabbitmqctl list_queues name leader messages_ready messages_unacknowledged consumers
```
```bash
docker compose run --rm topology-init
```
```bash
bash scripts/probar-pedidos.sh
```
```bash
docker exec pedidos360-kafka1 kafka-topics --bootstrap-server kafka1:29092 --describe --topic pedidos.eventos
```
```bash
docker exec pedidos360-kafka1 kafka-consumer-groups --bootstrap-server kafka1:29092 --describe --group pedidos360-report
```
```bash
bash scripts/probar-kafka.sh
```

## 8. Decisiones que puedes defender

| Decisión | Por qué |
|---|---|
| Monorepo | Microservicios = despliegue independiente, no repos separados. Cada `ms-*` tiene su pom y Dockerfile |
| React en vez de Angular | Tutorial del curso; mismo MSAL (`msal-browser`) |
| Mailpit | SMTP real sin enviar correos a personas; funciona sin internet |
| Políticas DLX en vez de argumentos | Se cambian sin recrear colas |
| Topología en `definitions.json` | Infraestructura como código: se recrea igual en AWS |
| Seguridad desactivable (`SECURITY_ENABLED`) | Desarrollar sin depender de Azure; en AWS siempre `true` |
| Rutas por método en API Gateway | `ANY` captura el preflight `OPTIONS` y CORS falla con 401 |
| Kafka **además** de RabbitMQ | Comandos a un trabajador (RabbitMQ) vs eventos para varios lectores que se guardan (Kafka) |
| Confluent 7.9 con ZooKeeper | La pauta pide ZooKeeper; Kafka 4 / Confluent 8 ya usan KRaft |
| Réplica 2 y `min.insync.replicas=1` | Con 2 brokers, permite seguir escribiendo si cae uno |
| Kafka en su propia EC2 | ec2-apps ya tiene 7 JVM; cada contenedor con límite de 768 MB |
| Oracle en EC2 y no en RDS | El Learner Lab bloquea RDS Oracle (*explicit deny*) |
