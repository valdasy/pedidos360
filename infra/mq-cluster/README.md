# infra/mq-cluster — Clúster RabbitMQ de 2 nodos (Parte 3)

## Probarlo en una máquina

```bash
cp .env.example .env
docker compose up -d
docker compose run --rm topology-init
docker exec pedidos360-rabbit1 rabbitmqctl cluster_status
```

UI del nodo 1: http://localhost:15682 · nodo 2: http://localhost:15683
(puertos distintos a `infra/mq` para poder tener ambos a la vez).

## Cómo se forma

| Pieza | Para qué |
|---|---|
| `hostname: rabbit1` / `rabbit2` | Nombre de cada nodo: `rabbit@rabbit1`, `rabbit@rabbit2` |
| `ERLANG_COOKIE` (igual en ambos) | Secreto con el que los nodos se autentican entre sí |
| `rabbitmq.conf` (`classic_config`) | Lista de nodos: al arrancar, cada uno busca a los demás y se une |

## Qué se comprobó (resultados reales)

| Prueba | Resultado |
|---|---|
| `cluster_status` | Running nodes: `rabbit@rabbit1`, `rabbit@rabbit2` |
| Topología cargada en el nodo 1 | Visible desde el nodo 2 (exchanges, colas y políticas se replican) |
| Publicar por el nodo 2, leer por el nodo 1 | Funciona: el clúster enruta al nodo donde vive la cola |
| `GET /api/mq-admin/overview` | Muestra los 2 nodos con memoria y disco |
| Se detiene el nodo 1 | Las colas del nodo 1 quedan **`down`** en el nodo 2 |
| Vuelve el nodo 1 | Se reincorpora solo al clúster |

## Lo importante para explicar: clúster ≠ alta disponibilidad

- En un clúster se comparten **metadatos** (usuarios, exchanges, colas, bindings, políticas)
  y los clientes pueden conectarse a cualquier nodo.
- Pero una cola **classic** vive en **un** nodo (su *leader*). Si ese nodo cae, la cola no
  está disponible hasta que vuelva (sus mensajes persistentes no se pierden: están en el disco
  de ese nodo).
- Para que los **mensajes** sobrevivan a la caída de un nodo se usan **quorum queues**
  (replicadas con el algoritmo Raft). Necesitan **mayoría**: con 3 nodos tolera 1 caído;
  con 2 nodos, si cae uno no hay mayoría. Por eso en producción se usan **3 nodos**.
- RabbitMQ 4 eliminó el "mirroring" de colas classic: la alta disponibilidad es con quorum queues.

## Conectar los microservicios al clúster

Spring acepta varios nodos y se reconecta al siguiente si uno cae:

```
SPRING_RABBITMQ_ADDRESSES=10.0.0.10:5672,10.0.0.11:5672
```

Para usarlo, agrégala en `infra/apps/compose.yml` dentro de `x-common-env` (reemplaza a
`RABBITMQ_HOST`). No se dejó por defecto porque con un solo broker no hace falta.

## En AWS con 2 EC2

Cada EC2 corre **un** nodo. Diferencias con la versión local:

1. Los nodos deben resolver sus nombres: en cada EC2 agregar en el servicio
   `extra_hosts: ["rabbit1:<IP privada EC2 1>", "rabbit2:<IP privada EC2 2>"]`.
2. Usar `network_mode: host` (Erlang usa puertos dinámicos además de los fijos).
3. Security Group entre las dos EC2: **4369** (epmd), **25672** (comunicación entre nodos),
   además de 5672/15672 para clientes.
4. Misma `ERLANG_COOKIE` en ambas.

Alternativa más simple y suficiente para la demo: ambos nodos en **una** EC2 con este
mismo `compose.yml`.
