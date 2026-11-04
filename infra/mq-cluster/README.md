# infra/mq-cluster — Broker principal: clúster RabbitMQ de 2 nodos (Parte 3)

Es el broker que usa todo Pedidos360: 2 nodos de RabbitMQ + Mailpit.
(`infra/mq` queda como alternativa de 1 nodo; no levantes ambos: usan los mismos puertos).

## Levantar

```bash
cp .env.example .env              # claves, erlang cookie y lista de nodos
docker compose up -d
docker compose run --rm topology-init
docker exec pedidos360-rabbit1 rabbitmqctl cluster_status
```

| | AMQP | UI |
|---|---|---|
| Nodo 1 (`rabbit@rabbit1`) | 5672 | http://localhost:15672 |
| Nodo 2 (`rabbit@rabbit2`) | 5673 | http://localhost:15673 |
| Mailpit | SMTP 1025 | http://localhost:8025 |

## Cómo se forma el clúster

| Pieza | Para qué |
|---|---|
| `hostname: rabbit1` / `rabbit2` | Nombre de cada nodo: `rabbit@rabbit1`, `rabbit@rabbit2` |
| `ERLANG_COOKIE` (igual en ambos) | Secreto con el que los nodos se autentican entre sí |
| `rabbitmq.conf` → `classic_config` | Lista de nodos: al arrancar, cada uno busca a los demás y se une |
| `rabbitmq.conf` → `queue_leader_locator = balanced` | Reparte las colas entre los nodos |

Reparto resultante (probado):

| Nodo 1 | Nodo 2 |
|---|---|
| `q.cmd.email`, `q.cmd.email.dlq`, `q.cmd.kitchen`, `q.cmd.kitchen.dlq` | `q.cmd.invoice`, `q.cmd.invoice.dlq` |

## Cómo se conectan los microservicios

| Servicio | Configuración | Efecto |
|---|---|---|
| orders, notify, mq-admin | `RABBITMQ_ADDRESSES=localhost:5672,localhost:5673` → `spring.rabbitmq.addresses` | Se conectan al primer nodo disponible; si cae, se reconectan al otro |
| mq-admin | `RABBITMQ_MANAGEMENT_URLS=...15672/api,...15673/api` | Si la API de un nodo no responde, consulta la del otro |
| notify | `missing-queues-fatal=false` | Si una cola queda no disponible, el listener reintenta en vez de detenerse |

## Qué pasa cuando cae un nodo (`scripts/probar-cluster.sh`, 17/17 OK)

| Escenario | Resultado |
|---|---|
| Cae el nodo 2 (invoice) | mq-admin alerta "Nodo caído" y "q.cmd.invoice no disponible". Los pedidos siguen; los correos (nodo 1) llegan; la nota de crédito no (orders lo registra como ERROR) |
| Vuelve el nodo 2 | Se reincorpora solo; notify vuelve a consumir invoice |
| Cae el nodo 1 (email, y donde estaban conectados los servicios) | orders, notify y mq-admin **se reconectan al nodo 2**. Los pedidos siguen; la nota de crédito (nodo 2) llega; el correo (nodo 1) no |
| Vuelve el nodo 1 | notify vuelve a consumir email y kitchen; todo funciona de nuevo |

## Problemas reales que encontramos al pasar al clúster

1. **Listener detenido para siempre.** Con una cola no disponible, Spring AMQP lanza
   `missing queues exception, fatal=true` y detiene el listener. Al volver el nodo nadie
   consumía. Solución: `spring.rabbitmq.listener.simple.missing-queues-fatal=false`.
2. **Un comando fallido bloqueaba los demás.** orders publicaba los comandos de un pedido en
   un solo `try`: si fallaba el correo (nodo 1 caído), ni siquiera intentaba la boleta (nodo 2
   disponible). Ahora cada comando se publica por separado.
3. **Publisher confirms detectan la pérdida.** Si la cola está caída, el broker responde `nack`
   y orders lo registra. Sin confirms el mensaje se perdería en silencio.

## Lo importante para explicar: clúster ≠ alta disponibilidad de mensajes

- El clúster comparte **metadatos** (usuarios, exchanges, colas, bindings, políticas), reparte
  colas entre nodos y permite que los clientes se conecten a cualquiera.
- Pero una cola **classic** vive en **un** nodo. Si ese nodo cae, la cola no está disponible
  hasta que vuelva (sus mensajes persistentes no se pierden: están en el disco de ese nodo).
- Para que la **cola** sobreviva a la caída de un nodo se usan **quorum queues** (replicadas con
  Raft). Necesitan **mayoría**: con 3 nodos toleran 1 caído; con 2 nodos, si cae uno no hay
  mayoría. Por eso en producción se usan **3 nodos**.
- RabbitMQ 4 eliminó el "mirroring" de colas classic: la alta disponibilidad es con quorum queues.
- Para no perder comandos cuando su cola está caída: patrón **Transactional Outbox**.

## En AWS

Ambos nodos en la **EC2 mq** con este mismo `compose.yml` (probado). En `infra/apps/.env`:

```
RABBITMQ_ADDRESSES=<IP privada ec2-mq>:5672,<IP privada ec2-mq>:5673
RABBITMQ_MANAGEMENT_URLS=http://<IP privada>:15672/api,http://<IP privada>:15673/api
```

Security Group de la EC2 mq: 5672, 5673, 15672, 15673 y 1025 desde `sg-pedidos360-apps`.

Variante con **un nodo por EC2** (más realista, no probada aquí):

1. `extra_hosts: ["rabbit1:<IP EC2 1>", "rabbit2:<IP EC2 2>"]` en el servicio de cada EC2.
2. `network_mode: host` (Erlang usa puertos dinámicos además de los fijos).
3. Security Group entre ambas: **4369** (epmd) y **25672** (comunicación entre nodos).
4. Misma `ERLANG_COOKIE` en ambas.
