#!/usr/bin/env bash
# =========================================================
# Pruebas de punta a punta: orders (productor) -> RabbitMQ -> notify (consumidor)
#
# Requisitos: infra/mq levantado (RabbitMQ + Mailpit), ms-orders en :8081 y
# ms-notify corriendo.  Uso (Git Bash, desde la raíz):  bash scripts/probar-mensajeria.sh
# =========================================================
set -uo pipefail

API=http://localhost:8081/api/orders/commands
MAILPIT=http://localhost:8025/api/v1
J='content-type: application/json'

post() {  # post <ruta> <json> [headers extra...] -> imprime el código HTTP
  local path=$1 body=$2; shift 2
  curl -s -o /tmp/p360-resp.json -w '%{http_code}' -X POST "$API/$path" -H "$J" "$@" -d "$body"
}
cola() {  # cola <nombre> -> "ready unacked"
  docker exec pedidos360-rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged \
    | tr -d '\r' | awk -v q="$1" '$1==q {print $2, $3}'
}
esperar_vacia() {  # espera a que el consumidor termine de procesar la cola
  for _ in $(seq 1 30); do [ "$(cola "$1")" = "0 0" ] && return; sleep 1; done
}
correos() {
  sleep 1   # Mailpit tarda un instante en indexar
  curl -s "$MAILPIT/messages" | grep -o '"total":[0-9]*' | cut -d: -f2
}
dlq() { cola "$1.dlq" | cut -d' ' -f1; }

echo "== Verificación previa"
if ! curl -s -o /dev/null --max-time 3 "$API/../../../actuator/health"; then
  echo "  ms-orders no responde en :8081. Levántalo con ./mvnw spring-boot:run"; exit 1
fi
consumidores=$(docker exec pedidos360-rabbitmq rabbitmqctl -q list_queues name consumers \
  | tr -d '\r' | awk '$1=="q.cmd.email" {print $2}')
if [ "${consumidores:-0}" -eq 0 ]; then
  echo "  q.cmd.email no tiene consumidores: ms-notify no está corriendo. Levántalo con ./mvnw spring-boot:run"; exit 1
fi
echo "  orders OK, notify conectado ($consumidores consumidor)"

ok=0; fail=0
check() {
  if [ "$2" = "$3" ]; then echo "  [OK]   $1 (=$3)"; ok=$((ok+1));
  else echo "  [FAIL] $1 (esperado $2, obtenido $3)"; fail=$((fail+1)); fi
}

echo "== 0. Preparación: vaciar DLQ y bandeja de Mailpit"
for q in email kitchen invoice; do docker exec pedidos360-rabbitmq rabbitmqctl -q purge_queue "q.cmd.$q.dlq" > /dev/null; done
curl -s -X DELETE "$MAILPIT/messages" > /dev/null

echo "== 1. Flujo feliz: los 3 comandos se procesan (ACK)"
check "POST email -> 202"   202 "$(post email '{"pedidoId":501,"email":"cliente@ejemplo.cl","asunto":"Pedido confirmado","mensaje":"Su pedido 501 fue confirmado"}')"
check "POST kitchen -> 202" 202 "$(post kitchen '{"pedidoId":201,"productos":[{"nombre":"Cafe americano","cantidad":2}]}')"
check "POST invoice -> 202" 202 "$(post invoice '{"pedidoId":301,"cliente":"Comercial Ejemplo Ltda.","monto":25990,"documento":"FACTURA"}')"
esperar_vacia q.cmd.email
check "Correos en Mailpit" 1 "$(correos)"

echo "== 2. Exchange topic: variante de prioridad"
check "email?priority=high (email.send.high -> email.#) -> 202" 202 \
  "$(post 'email?priority=high' '{"pedidoId":502,"email":"vip@ejemplo.cl","asunto":"Urgente","mensaje":"Prioridad alta"}')"
check "invoice?priority=high (invoice.gen.high no calza con invoice.*) -> 422" 422 \
  "$(post 'invoice?priority=high' '{"pedidoId":302,"cliente":"X","monto":1000}')"
esperar_vacia q.cmd.email
check "Correos en Mailpit" 2 "$(correos)"

echo "== 3. Idempotencia: mismo X-Event-Id dos veces = un solo correo"
EID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || powershell -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '\r')
post email '{"pedidoId":503,"email":"dup@ejemplo.cl","asunto":"Duplicado","mensaje":"Solo debe llegar uno"}' -H "X-Event-Id: $EID" > /dev/null
post email '{"pedidoId":503,"email":"dup@ejemplo.cl","asunto":"Duplicado","mensaje":"Solo debe llegar uno"}' -H "X-Event-Id: $EID" > /dev/null
esperar_vacia q.cmd.email
check "Correos en Mailpit (2 + 1)" 3 "$(correos)"

echo "== 4. Error permanente: datos inválidos -> REJECT -> DLQ"
post email '{"pedidoId":504,"email":"sin-arroba","asunto":"x","mensaje":"x"}' > /dev/null
post kitchen '{"pedidoId":202,"productos":[]}' > /dev/null
post invoice '{"pedidoId":303,"cliente":"X","monto":0}' > /dev/null
esperar_vacia q.cmd.email; esperar_vacia q.cmd.kitchen; esperar_vacia q.cmd.invoice
check "q.cmd.email.dlq"   1 "$(dlq q.cmd.email)"
check "q.cmd.kitchen.dlq" 1 "$(dlq q.cmd.kitchen)"
check "q.cmd.invoice.dlq" 1 "$(dlq q.cmd.invoice)"

echo "== 5. Error transitorio: SMTP caído -> NACK (reintento) -> REJECT -> DLQ"
docker stop pedidos360-mailpit > /dev/null
post email '{"pedidoId":505,"email":"cliente@ejemplo.cl","asunto":"SMTP caido","mensaje":"x"}' > /dev/null
esperar_vacia q.cmd.email
docker start pedidos360-mailpit > /dev/null
check "q.cmd.email.dlq (1 + 1)" 2 "$(dlq q.cmd.email)"

echo
echo "Resultado: $ok OK, $fail FAIL"
echo "Revisa: Mailpit http://localhost:8025 | RabbitMQ http://localhost:15672 | logs de notify"
[ "$fail" -eq 0 ]
