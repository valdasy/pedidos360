# Etapa 9 — Apache Kafka (Evaluación 3)

Kafka se suma a RabbitMQ **sin reemplazarlo**: cada broker hace lo que mejor sabe hacer.

| | RabbitMQ (Unidad 2) | Kafka (Evaluación 3) |
|---|---|---|
| Qué viaja | **Comandos**: "envía este correo", "genera la boleta" | **Eventos de negocio**: "el pedido 7 pasó a ACEPTADO" |
| Quién lo recibe | **Un** consumidor (cola de trabajo) | **Varios** consumer groups, cada uno con su copia |
| Después de consumir | El mensaje se borra (ACK) | El evento **queda** en el log (retención 7 días) y se puede releer |
| Orden | Por cola | Por **partición** (misma clave → misma partición → en orden) |
| Error | DLQ (`*.dlq`) | DLT (`*.DLT`) |

## Arquitectura

```
                         ┌──────────── pedidos.eventos (3 particiones, réplica 2, clave = pedidoId) ─────────┐
ms-orders ──(productor)──┤                                                                                    │
                         │        ┌── grupo pedidos360-audit ──► ms-audit  ──► Oracle (pedidos_audit)    /api/audit
                         └────────┤
                                  └── grupo pedidos360-report ─► ms-report ──► Oracle (pedidos_report)   /api/report
ms-catalog ─(productor)── inventario.eventos (3 particiones, réplica 2, clave = productoId) ──► ms-audit

        Errores: *.DLT (3 particiones, retención 30 días)        Administración: ms-kafka-admin + Kafka UI
        Clúster: ZooKeeper + broker 1 (:9092) + broker 2 (:9093)
```

## Qué pide la pauta y dónde está

| Pauta | Implementación |
|---|---|
| docker-compose con ZooKeeper, brokers y Kafka UI | `infra/kafka/compose.yml`: ZooKeeper + `kafka1` + `kafka2` + Kafka UI (`:8090`) |
| Dos tópicos con particiones y réplicas | `pedidos.eventos` e `inventario.eventos`: 3 particiones, réplica 2 (`infra/kafka/crear-topicos.sh`) |
| Parámetros de tópicos | `retention.ms` (7 días; DLT 30), `cleanup.policy=delete`, `min.insync.replicas=1` |
| Configuración de clientes | `application.properties` de cada servicio (`spring.kafka.*`) |
| Productores con DTO tipados | `PedidoEventPublisher` (orders) y `InventarioEventPublisher` (catalog): records `PedidoEvento` e `InventarioEvento`, `JacksonJsonSerializer` |
| Consumidores | `AuditListener` (2 tópicos) y `ReporteListener` (`@KafkaListener` con `ConsumerRecord<String, DTO>`) |
| Consumer groups | `pedidos360-audit` y `pedidos360-report` leen el mismo tópico de forma independiente |
| Errores y reintentos | `KafkaErrorConfig`: `DefaultErrorHandler` (3 reintentos, backoff 1-2-4 s) + `DeadLetterPublishingRecoverer` → `*.DLT` |
| Validación y logging | `validar()` en cada DTO → `InvalidEventException` (sin reintento); logs con tópico-partición-offset |
| Microservicio administrador | `ms-pedidos360-kafka-admin` (`AdminClient`): crear, listar, describir, modificar configs, aumentar particiones, eliminar, grupos y LAG |
| RabbitMQ conservado | Sin cambios: comandos, DLQ, clúster de 2 nodos y mq-admin siguen igual |

## Decisiones de diseño (para explicar)

**1. Clave del mensaje = pedidoId.** Kafka elige la partición con `hash(clave) % particiones`.
Todos los eventos del pedido 7 caen en la misma partición y llegan **en orden**: ms-report
nunca ve ENTREGADO antes que CREADO. Comprobado: los 5 eventos de un pedido tienen el mismo
número de partición en el timeline de auditoría.

**2. Productor seguro.** `acks=all` (el broker responde cuando el evento está en todas las réplicas
en sincronía) + `enable.idempotence=true` (un reintento de red no duplica el evento en la partición).
`max.block.ms=5000`: si Kafka no está, la petición HTTP no se queda colgada.

**3. Publicar después del commit.** `@TransactionalEventListener(AFTER_COMMIT)`: solo se publica lo
que quedó guardado en Oracle. Limitación honesta: si Kafka cae justo después del commit, el evento se
pierde (queda en el log). La solución completa es el patrón **Transactional Outbox** (igual que en RabbitMQ).

**4. DTO tipados sin acoplar los servicios.** El productor serializa a JSON **sin cabeceras de tipo
Java** (`spring.json.add.type.headers=false`). Cada consumidor tiene su **propia copia** del record y le
dice al deserializador a qué clase convertir (`spring.json.value.default.type` en cada `@KafkaListener`).
En el consumidor, `tipo` y `estado` son `String`: si el productor agrega un estado nuevo, el consumidor
no falla (compatibilidad hacia adelante). El campo `version` permite evolucionar el contrato.

**5. ErrorHandlingDeserializer.** Envuelve al deserializador JSON. Si llega un mensaje mal formado, no
revienta el consumidor en un bucle infinito ("poison pill"): el error pasa al `DefaultErrorHandler`.

**6. Errores transitorios vs permanentes.**

| Error | Ejemplo | Qué pasa |
|---|---|---|
| Transitorio | Oracle no responde | 3 reintentos (1 s, 2 s, 4 s) sin avanzar el offset. Si se recupera, el evento se procesa. Si no, va al DLT |
| Permanente | JSON mal formado, falta `pedidoId` | Directo al DLT, **sin reintentos** (fallaría igual) |

En ambos casos el consumidor **sigue** con el siguiente evento (LAG vuelve a 0). En el DLT, las
cabeceras `kafka_dlt-exception-message` y `kafka_dlt-original-consumer-group` explican el fallo.
Como cada grupo maneja sus errores, un evento malo aparece **dos veces** en el DLT (uno por grupo).

**7. Idempotencia (Kafka entrega "al menos una vez").** Si un consumidor procesa un evento y se cae
antes de confirmar el offset, al volver lo recibe otra vez.
- ms-audit: `event_id` UNIQUE en la tabla.
- ms-report: el `eventId` se guarda en `rep_evento_procesado` **en la misma transacción** que actualiza
  los KPIs: o se guardan ambos o ninguno. Un duplicado no suma dos veces.

**8. Offsets.** `enable-auto-commit=false` + `ack-mode=record`: Spring confirma el offset después de
procesar cada evento, no con un temporizador.

**9. Consumer groups y particiones.** Con 3 particiones, un grupo puede tener hasta 3 consumidores
activos. Comprobado con 2 instancias de ms-audit: una recibió las particiones 0 y 1, la otra la 2.
`concurrency=1` por defecto para que ese reparto se vea claramente.

**10. Réplicas.** Réplica 2 + `min.insync.replicas=1`: si cae un broker, el otro tiene copia de cada
partición, asume como líder y se sigue escribiendo. Con 3 brokers se usaría réplica 3 y `min.insync=2`
(no perder datos aunque caiga uno *y* seguir escribiendo).

**11. Database per service.** ms-report no consulta la base de ms-orders: construye su propia
"proyección" (`rep_pedido`) solo con eventos. Si se borra, se puede reconstruir releyendo el tópico
desde el inicio (grupo nuevo + `auto-offset-reset=earliest`).

## Levantar en local

```bash
# 1. Kafka
cd infra/kafka && cp .env.example .env
docker compose up -d
docker compose --profile init run --rm topics-init     # crea los 4 tópicos
cd ../..

# 2. Usuarios Oracle de audit y report (base ya existente; en una base nueva se crean solos)
#    Agregar AUDIT_DB_PASSWORD y REPORT_DB_PASSWORD a infra/db/.env
bash infra/db/crear-usuarios-kafka.sh

# 3. Microservicios nuevos (además de catalog, orders, notify)
cd ms-pedidos360-audit       && ./mvnw spring-boot:run   # :8086
cd ms-pedidos360-report      && ./mvnw spring-boot:run   # :8087
cd ms-pedidos360-kafka-admin && ./mvnw spring-boot:run   # :8088

# 4. Pruebas
bash scripts/probar-kafka.sh     # 40 pruebas
```

| | URL |
|---|---|
| Kafka UI | http://localhost:8090 (usuario/clave de `infra/kafka/.env`) |
| Swagger audit / report / kafka-admin | http://localhost:8086/swagger-ui.html · :8087 · :8088 |
| Frontend | Reportes (`/reports`), Auditoría (`/audit`), Kafka (`/admin/kafka`) |

## Pruebas (`scripts/probar-kafka.sh`, 40/40 OK)

| Bloque | Qué comprueba |
|---|---|
| Clúster y tópicos | 2 brokers, 3 particiones, réplica 2, ISR completo, retención 7 días, DLT existen |
| Ciclo completo | Pedido creado → entregado: 7 eventos en auditoría (5 del pedido + 2 de stock), todos los del pedido en la misma partición; KPIs actualizados |
| Cancelación | PEDIDO_CANCELADO + STOCK_LIBERADO; cancelados +1 |
| Idempotencia | Se republica un evento ya procesado: ni audit ni report lo cuentan de nuevo |
| JSON mal formado | Va al DLT (una copia por grupo) |
| Evento inválido | Va al DLT sin reintentos, con el motivo en la cabecera; LAG vuelve a 0 |
| Consumer groups | Ambos STABLE; audit tiene las 3 particiones |
| Administración | Crear (201), duplicado (409), réplica 3 con 2 brokers (400), modificar retención, aumentar particiones, **reducir (400)**, eliminar (204) |

Probado además a mano:

- **Oracle congelado** (`docker pause pedidos360-oracle`): los intentos 1 y 2 fallan (`ORA-17008`),
  se reanuda Oracle y el intento 3 tiene éxito, sin pasar por el DLT.
- **Cae un broker** (`docker stop pedidos360-kafka2`): el broker 1 pasa a ser líder de las 3
  particiones (ISR = [1]); se crea un pedido y el evento llega igual a auditoría. Al volver kafka2,
  se resincroniza solo (ISR = [1, 2]).
- **2 instancias de ms-audit** (mismo grupo): una recibe las particiones 0 y 1, la otra la 2.

## Guion de demo (5 min)

1. **Kafka UI** (`:8090`): 2 brokers, tópicos con 3 particiones y réplica 2.
2. Frontend como Operador: crear un pedido y avanzarlo a ENTREGADO.
3. **Reportes**: los KPIs cambian solos (se refrescan cada 5 s).
4. Como Admin, **Auditoría** → buscar el pedido: timeline con tópico-partición@offset; todos en la misma partición.
5. **Kafka** (admin): consumer groups con LAG 0; detalle del grupo con las particiones asignadas.
6. Errores: en Swagger de kafka-admin, `POST /topics/pedidos.eventos/messages` con `{"clave":"x","valor":"no es json"}`
   → en **Kafka → pedidos.eventos.DLT → Mensajes** aparece con el motivo, y el LAG sigue en 0.
7. (Opcional) Levantar una 2.ª instancia de ms-audit y mostrar cómo se reparten las particiones.

## Despliegue en AWS: EC2 `kafka`

Kafka va en su propia EC2 (los 7 microservicios ya ocupan `ec2-apps`).

### 1. Security Group `pedidos360-kafka`

| Tipo / puerto | Origen | Para qué |
|---|---|---|
| SSH 22 | Mi IP + `18.206.107.24/29` | Conexión |
| TCP 9092, 9093 | `pedidos360-apps` | Brokers (productores y consumidores) |
| TCP 8090 | Mi IP | Kafka UI para la demo |

ZooKeeper (2181) **no** se abre: solo lo usan los brokers dentro de la red de Docker.
Salida: dejar *Todo el tráfico 0.0.0.0/0*.

### 2. Lanzar `ec2-kafka`

Amazon Linux 2023, **t3.medium**, vockey, 20 GiB, SG `pedidos360-kafka`, user data
`infra/aws/ec2-user-data.sh`. Anotar su **IP privada**.

```bash
git clone https://github.com/valdasy/pedidos360.git && cd ~/pedidos360/infra/kafka
cp .env.example .env && nano .env
```

- `KAFKA_ADVERTISED_HOST=<IP privada de ec2-kafka>` ← la dirección que los brokers entregan a los clientes
- `KAFKA_UI_PASS=<clave>` y `KAFKA_UI_BIND=0.0.0.0`

```bash
docker compose up -d && docker compose --profile init run --rm topics-init
```

UI: `http://<IP pública ec2-kafka>:8090`.

> **Por qué `KAFKA_ADVERTISED_HOST`:** el cliente se conecta primero a cualquier broker (bootstrap) y
> este le responde "para la partición 2 habla con el broker 1 en X:9092". Si X fuera `kafka1` (nombre
> interno de Docker), `ec2-apps` no podría resolverlo (`UnknownHostException`).

### 3. Oracle (`ec2-db`)

Agregar a `~/pedidos360/infra/db/.env` (solo letras y números):

```
AUDIT_DB_PASSWORD=...
REPORT_DB_PASSWORD=...
```

```bash
cd ~/pedidos360 && git pull && bash infra/db/crear-usuarios-kafka.sh
```

### 4. Microservicios (`ec2-apps`)

```bash
cd ~/pedidos360 && git pull && cd infra/apps && nano .env
```

Agregar:

```
KAFKA_BOOTSTRAP_SERVERS=<IP privada ec2-kafka>:9092,<IP privada ec2-kafka>:9093
AUDIT_DB_PASSWORD=<igual que en ec2-db>
REPORT_DB_PASSWORD=<igual que en ec2-db>
```

```bash
docker compose up -d --build
docker compose ps        # 7 servicios Up
```

Cada contenedor queda limitado a 768 MB (`APP_MEM_LIMIT`): 7 JVM caben en la t2/t3.large.
Agregar al SG `pedidos360-apps` los puertos **8086, 8087 y 8088** (Anywhere-IPv4, como 8081-8085).

### 5. API Gateway (CloudShell)

```bash
cd ~/pedidos360 && git pull && cd infra/aws
EC2_APPS_HOST=<Elastic IP> bash api-gateway-kafka.sh
```

Agrega `/api/audit`, `/api/report` y `/api/kafka-admin` al gateway existente con el mismo autorizador JWT.

### Problemas frecuentes

| Síntoma | Causa | Solución |
|---|---|---|
| `UnknownHostException: kafka1` en audit/report | `KAFKA_ADVERTISED_HOST` quedó en `localhost` | IP privada de ec2-kafka y `docker compose up -d` |
| `Connection to node -1 could not be established` | Falta 9092/9093 desde `pedidos360-apps` | Security Group |
| `Topic pedidos.eventos not present in metadata` (orders) | No se ejecutó `topics-init` | `docker compose --profile init run --rm topics-init` |
| Solo aparece 1 broker | El otro no arrancó (memoria) | `docker compose logs kafka2`; t3.medium mínimo |
| audit/report: `ORA-01017` | Clave distinta entre `infra/db/.env` y `infra/apps/.env` | Igualarlas |
| No se puede crear un tópico con réplica 3 | Solo hay 2 brokers | Réplica ≤ 2 |

## Preguntas probables del profesor

- **¿Por qué Kafka y RabbitMQ a la vez?** RabbitMQ para comandos que debe ejecutar un solo trabajador
  y luego desaparecen; Kafka para eventos que interesan a varios consumidores y que conviene guardar
  y poder releer (auditoría, analítica).
- **¿Cómo garantizan el orden?** Por partición. La clave es el pedidoId, así que todos los eventos de un
  pedido van a la misma partición. Entre pedidos distintos no hay orden global (ni hace falta).
- **¿Qué pasa si cae un broker?** Cada partición tiene réplica en el otro broker: este pasa a ser
  líder y con `min.insync.replicas=1` se sigue escribiendo y leyendo.
- **¿Qué pasa si cae ms-report?** Los eventos se acumulan en Kafka (aumenta el LAG). Al volver, continúa
  desde su último offset confirmado; no se pierde nada.
- **¿Y si se procesa dos veces un evento?** Idempotencia por `eventId` (UNIQUE / tabla de procesados).
- **¿Por qué no se pueden reducir particiones?** Habría que mover o eliminar mensajes y se rompería el
  orden por clave. Kafka solo permite aumentarlas (y advierte: las claves pueden cambiar de partición).
- **¿Diferencia entre DLQ y DLT?** Conceptualmente lo mismo. En RabbitMQ el mensaje sale de la cola; en
  Kafka el original sigue en el tópico y el DLT es una copia para revisar o reprocesar.
- **¿ZooKeeper sigue vigente?** Desde Kafka 4 se reemplazó por KRaft. Usamos Confluent 7.9 porque la
  pauta pide ZooKeeper.
