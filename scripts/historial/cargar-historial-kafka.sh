#!/usr/bin/env bash
# Publica los eventos del historial en el tópico pedidos.eventos (los leen ms-audit y ms-report).
# Ejecutar en la máquina donde corre Kafka (local o ec2-kafka), EL MISMO DÍA que
# cargar-historial-db.sh: el generador es determinista por fecha, así ambos lados coinciden.
#   bash scripts/historial/cargar-historial-kafka.sh [dias]
# Si se ejecuta dos veces no duplica nada: los eventId son fijos y los consumidores son idempotentes.
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1

python3 generar_historial.py --dias "${1:-90}" --salida . 2>/dev/null \
  || python generar_historial.py --dias "${1:-90}" --salida .
echo "Publicando $(wc -l < historial-eventos.txt) eventos en pedidos.eventos..."
# Misma clave (pedidoId) y mismo particionador que el productor real: cada pedido cae en su partición
docker exec -i pedidos360-kafka1 kafka-console-producer --bootstrap-server kafka1:29092 \
  --topic pedidos.eventos --property parse.key=true --property key.separator='|' \
  --producer-property acks=all < historial-eventos.txt
echo "Listo. Revisa el LAG de pedidos360-report y pedidos360-audit en Kafka UI (debe bajar a 0)."
