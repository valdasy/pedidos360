#!/usr/bin/env bash
# Crea los tópicos de Pedidos360 (idempotente: --if-not-exists).
# Se ejecuta con: docker compose run --rm topics-init
set -euo pipefail

BS=kafka1:29092,kafka2:29092
SEVEN_DAYS=604800000
THIRTY_DAYS=2592000000

crear() {  # crear <tópico> <particiones> <retención ms>
  kafka-topics --bootstrap-server "$BS" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 2 \
    --config retention.ms="$3" \
    --config cleanup.policy=delete \
    --config min.insync.replicas=1
  echo "  ok: $1 ($2 particiones, réplica 2, retención $(( $3 / 86400000 )) días)"
}

echo "== Tópicos de negocio"
# Clave = pedidoId: todos los eventos de un pedido van a la MISMA partición -> llegan en orden
crear pedidos.eventos 3 "$SEVEN_DAYS"
# Clave = productoId: los movimientos de stock de un producto llegan en orden
crear inventario.eventos 3 "$SEVEN_DAYS"

echo "== Dead Letter Topics (eventos que fallaron tras los reintentos)"
# Misma cantidad de particiones: el DLT conserva la partición original del evento
crear pedidos.eventos.DLT 3 "$THIRTY_DAYS"
crear inventario.eventos.DLT 3 "$THIRTY_DAYS"

echo
kafka-topics --bootstrap-server "$BS" --describe --topic pedidos.eventos
kafka-topics --bootstrap-server "$BS" --describe --topic inventario.eventos
