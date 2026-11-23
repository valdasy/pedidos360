# infra/kafka — Clúster Kafka (Evaluación 3)

ZooKeeper + 2 brokers (Confluent Platform 7.9) + Kafka UI. Guía completa: [docs/etapa9-kafka.md](../../docs/etapa9-kafka.md).

```bash
cp .env.example .env
docker compose up -d
docker compose --profile init run --rm topics-init     # crea los tópicos (idempotente)
```

| Servicio | Puerto | Uso |
|---|---|---|
| `kafka1` | 9092 | Broker 1 (listener EXTERNAL para los microservicios) |
| `kafka2` | 9093 | Broker 2 |
| `zookeeper` | — (solo red interna) | Metadatos del clúster y elección del controlador |
| `kafka-ui` | 8090 | http://localhost:8090 (usuario/clave del `.env`) |

| Tópico | Particiones | Réplicas | Retención | Clave | Productor → consumidores |
|---|---|---|---|---|---|
| `pedidos.eventos` | 3 | 2 | 7 días | pedidoId | orders → audit, report |
| `inventario.eventos` | 3 | 2 | 7 días | productoId | catalog → audit |
| `pedidos.eventos.DLT` | 3 | 2 | 30 días | (original) | errores de audit y report |
| `inventario.eventos.DLT` | 3 | 2 | 30 días | (original) | errores de audit |

Comandos útiles:

```bash
docker exec pedidos360-kafka1 kafka-topics --bootstrap-server kafka1:29092 --describe --topic pedidos.eventos
docker exec pedidos360-kafka1 kafka-consumer-groups --bootstrap-server kafka1:29092 --describe --group pedidos360-report
docker exec pedidos360-zookeeper zookeeper-shell localhost:2181 ls /brokers/ids     # [1, 2]
```

`docker compose down` detiene sin borrar datos. **No** usar `down -v`: borra los volúmenes (todos los eventos).
