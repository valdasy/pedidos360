#!/usr/bin/env bash
# =========================================================
# Pruebas de la topología Pedidos360 usando la API HTTP de RabbitMQ.
# Reproduce las pruebas de la guía Parte 1, pero automatizadas.
#
# Uso (Git Bash, desde infra/mq):   bash scripts/verificar-topologia.sh
# Requiere: broker levantado y topología cargada (topology-init).
# =========================================================
set -euo pipefail

cd "$(dirname "$0")/.."
source .env
API="http://localhost:15672/api"
AUTH="$RABBITMQ_USER:$RABBITMQ_PASS"
VH="%2F"   # el vhost "/" codificado para URL

# Publica en un exchange y devuelve "true" si el mensaje fue enrutado a alguna cola
publicar() {
  local exchange=$1 rk=$2 body=$3
  curl -s -u "$AUTH" -H "content-type: application/json" -X POST \
    "$API/exchanges/$VH/$exchange/publish" \
    -d "{\"properties\":{\"delivery_mode\":2,\"content_type\":\"application/json\"},\"routing_key\":\"$rk\",\"payload\":$(printf '%s' "$body" | sed 's/"/\\"/g; s/^/"/; s/$/"/'),\"payload_encoding\":\"string\"}" \
    | grep -o '"routed":[a-z]*' | cut -d: -f2
}

# Cantidad de mensajes "Ready" de una cola.
# Se usa rabbitmqctl porque es tiempo real; las estadísticas de la API HTTP
# (y de la UI) se refrescan cada ~5 segundos.
ready() {
  docker exec pedidos360-rabbitmq rabbitmqctl -q list_queues name messages_ready \
    | tr -d '\r' | awk -v q="$1" '$1==q {print $2}'
}

# Retira mensajes de una cola. ackmode: ack_requeue_false | reject_requeue_false | ack_requeue_true
obtener() {
  curl -s -u "$AUTH" -H "content-type: application/json" -X POST \
    "$API/queues/$VH/$1/get" -d "{\"count\":${3:-1},\"ackmode\":\"$2\",\"encoding\":\"auto\"}"
}

purgar() { curl -s -u "$AUTH" -X DELETE "$API/queues/$VH/$1/contents" > /dev/null; }

ok=0; fail=0
check() {  # check "descripción" esperado obtenido
  if [ "$2" = "$3" ]; then echo "  [OK]   $1 (=$3)"; ok=$((ok+1));
  else echo "  [FAIL] $1 (esperado $2, obtenido $3)"; fail=$((fail+1)); fi
}

echo "== 0. Limpieza de colas"
for q in email kitchen invoice; do purgar "q.cmd.$q"; purgar "q.cmd.$q.dlq"; done

echo "== 1. Exchange DIRECT (coincidencia exacta)"
check "cmd.direct email.send se enruta"           true  "$(publicar cmd.direct email.send '{"pedidoId":102,"tipo":"EMAIL"}')"
check "cmd.direct email.send.high NO se enruta"   false "$(publicar cmd.direct email.send.high '{"pedidoId":103}')"
check "cmd.direct kitchen.ticket se enruta"       true  "$(publicar cmd.direct kitchen.ticket '{"pedidoId":201,"tipo":"KITCHEN_TICKET"}')"
check "cmd.direct invoice.gen se enruta"          true  "$(publicar cmd.direct invoice.gen '{"pedidoId":301,"tipo":"INVOICE"}')"

echo "== 2. Exchange TOPIC (patrones * y #)"
check "cmd.topic email.send.high -> email.#"      true  "$(publicar cmd.topic email.send.high '{"pedidoId":104}')"
check "cmd.topic kitchen.ticket.thermal -> kitchen.#" true "$(publicar cmd.topic kitchen.ticket.thermal '{"pedidoId":202}')"
check "cmd.topic invoice.gen -> invoice.*"        true  "$(publicar cmd.topic invoice.gen '{"pedidoId":302}')"
check "cmd.topic invoice.gen.pdf NO coincide con invoice.*" false "$(publicar cmd.topic invoice.gen.pdf '{"pedidoId":303}')"

echo "== 3. Contadores Ready"
check "q.cmd.email Ready"   2 "$(ready q.cmd.email)"
check "q.cmd.kitchen Ready" 2 "$(ready q.cmd.kitchen)"
check "q.cmd.invoice Ready" 2 "$(ready q.cmd.invoice)"

echo "== 4. DLX/DLQ: Reject requeue=false mueve a la DLQ"
for q in email kitchen invoice; do
  obtener "q.cmd.$q" reject_requeue_false 1 > /dev/null
  check "q.cmd.$q.dlq Ready tras rechazo" 1 "$(ready q.cmd.$q.dlq)"
  check "q.cmd.$q Ready restante"         1 "$(ready q.cmd.$q)"
done

echo "== 5. Cabecera x-death del mensaje muerto en q.cmd.email.dlq"
obtener q.cmd.email.dlq ack_requeue_true 1 | grep -o '"x-death":\[{[^]]*' | head -c 400; echo

echo
echo "Resultado: $ok OK, $fail FAIL"
[ "$fail" -eq 0 ]
