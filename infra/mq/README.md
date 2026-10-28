# infra/mq — RabbitMQ (IL 2.1)

Broker RabbitMQ con la topología de comandos de Pedidos360.

## Archivos

| Archivo | Para qué sirve |
|---|---|
| `compose.yml` | Levanta RabbitMQ 4.1 con Management UI. El servicio `topology-init` carga la topología |
| `.env.example` | Plantilla de credenciales. Copiar como `.env` (no se sube a GitHub) |
| `definitions.json` | Topología: 3 exchanges, 6 colas, 9 bindings, 3 políticas DLX |
| `scripts/verificar-topologia.sh` | 17 pruebas automáticas de enrutamiento y DLQ |

## Levantar desde cero

```bash
cp .env.example .env          # y editar la contraseña
docker compose up -d          # broker
docker compose run --rm topology-init   # carga definitions.json
bash scripts/verificar-topologia.sh     # pruebas
```

UI: http://localhost:15672 (usuario y clave del `.env`). AMQP: `localhost:5672`.

## Topología

| Exchange | Tipo | Cola | Binding |
|---|---|---|---|
| `cmd.direct` | direct | `q.cmd.email` | `email.send` |
| `cmd.direct` | direct | `q.cmd.kitchen` | `kitchen.ticket` |
| `cmd.direct` | direct | `q.cmd.invoice` | `invoice.gen` |
| `cmd.topic` | topic | `q.cmd.email` | `email.#` |
| `cmd.topic` | topic | `q.cmd.kitchen` | `kitchen.#` |
| `cmd.topic` | topic | `q.cmd.invoice` | `invoice.*` |
| `cmd.dead.dlx` | direct | `q.cmd.email.dlq` | `email.send` |
| `cmd.dead.dlx` | direct | `q.cmd.kitchen.dlq` | `kitchen.ticket` |
| `cmd.dead.dlx` | direct | `q.cmd.invoice.dlq` | `invoice.gen` |

| Política | Patrón | dead-letter-exchange | dead-letter-routing-key |
|---|---|---|---|
| `policy.email.dlx` | `^q\.cmd\.email$` | `cmd.dead.dlx` | `email.send` |
| `policy.kitchen.dlx` | `^q\.cmd\.kitchen$` | `cmd.dead.dlx` | `kitchen.ticket` |
| `policy.invoice.dlx` | `^q\.cmd\.invoice$` | `cmd.dead.dlx` | `invoice.gen` |

## Decisiones

- **Credenciales en `.env`** y no en `definitions.json`: si el archivo de definiciones
  incluyera usuarios, la clave quedaría en GitHub. Además, cargar definiciones al
  arrancar (`load_definitions`) desactiva la creación del usuario por defecto, que es
  la causa del error `Not_authorized` descrito en la guía Parte 2.
- **Políticas en vez de argumentos `x-dead-letter-*`**: se pueden cambiar sin recrear
  la cola.
- **`email.#`** en el topic (ajuste de la guía Parte 1) para aceptar `email.send.high`.
  `invoice.*` se mantiene como en el caso: `invoice.gen.pdf` no se enruta (lo demuestra
  la prueba 2).
- **`hostname: rabbitmq` fijo**: RabbitMQ guarda los datos por nombre de nodo
  (`rabbit@<hostname>`). Si el hostname cambiara en cada contenedor, se perderían los
  datos del volumen.
