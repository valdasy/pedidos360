#!/usr/bin/env bash
# =========================================================
# Pruebas de ms-pedidos360-mq-admin (administración de colas, IL 2.3)
#
# Requisitos: infra/mq, ms-orders (:8081), ms-notify y ms-mq-admin (:8085) corriendo.
# Uso (Git Bash, desde la raíz):  bash scripts/probar-admin.sh
# =========================================================
set -uo pipefail

ADMIN=http://localhost:8085/api/mq-admin
ORDERS=http://localhost:8081/api/orders/commands
MAILPIT=http://localhost:8025/api/v1
J='content-type: application/json'

dlq_ready() {   # tiempo real con rabbitmqctl (la API HTTP tarda ~5 s en refrescar)
  docker exec pedidos360-rabbitmq rabbitmqctl -q list_queues name messages_ready \
    | tr -d '\r' | awk -v q="q.cmd.$1.dlq" '$1==q {print $2}'
}
esperar() { sleep "${1:-3}"; }
asunto_en_mailpit() { curl -s "$MAILPIT/messages" | grep -c "\"Subject\":\"$1\""; }
campo() { grep -o "\"$1\":[0-9]*" | head -1 | cut -d: -f2; }

ok=0; fail=0
check() {
  if [ "$2" = "$3" ]; then echo "  [OK]   $1 (=$3)"; ok=$((ok+1));
  else echo "  [FAIL] $1 (esperado $2, obtenido $3)"; fail=$((fail+1)); fi
}

echo "== Verificación previa"
for url in "$ADMIN/overview" "http://localhost:8081/actuator/health"; do
  curl -sf -o /dev/null --max-time 3 "$url" || { echo "  No responde $url"; exit 1; }
done
echo "  mq-admin y orders OK"
consumidores=$(docker exec pedidos360-rabbitmq rabbitmqctl -q list_queues name consumers \
  | tr -d '\r' | awk '$1=="q.cmd.email" {print $2}')
if [ "${consumidores:-0}" -ne 1 ]; then
  echo "  AVISO: q.cmd.email tiene ${consumidores:-0} consumidores; estas pruebas esperan exactamente 1 notify."
fi

echo "== 0. Preparación: purgar DLQ de email"
curl -s -X DELETE "$ADMIN/flows/email/dlq/messages" > /dev/null
check "DLQ email vacía" 0 "$(dlq_ready email)"

echo "== 1. Generar 2 fallos: SMTP caído (recuperable) y correo inválido (no recuperable)"
docker stop pedidos360-mailpit > /dev/null
# Sin tildes en los datos: en Windows, curl.exe recibe los argumentos en cp1252 y no en
# UTF-8, y orders rechaza el JSON con 400 (Postman y Swagger no tienen este problema).
check "POST 901 -> 202" 202 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ORDERS/email" -H "$J" \
  -d '{"pedidoId":901,"email":"cliente@ejemplo.cl","asunto":"Recuperado desde DLQ","mensaje":"Este correo fallo y se reproceso"}')"
check "POST 902 -> 202" 202 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ORDERS/email" -H "$J" \
  -d '{"pedidoId":902,"email":"sin-arroba","asunto":"x","mensaje":"x"}')"
# 901 tarda: cada intento espera el timeout SMTP (5 s) y se intenta 2 veces
for _ in $(seq 1 40); do [ "$(dlq_ready email)" = "2" ] && break; sleep 1; done
docker start pedidos360-mailpit > /dev/null
esperar 3
check "DLQ email con 2 mensajes" 2 "$(dlq_ready email)"

echo "== 2. Ver la DLQ sin retirar mensajes (motivo x-death)"
curl -s "$ADMIN/flows/email/dlq/messages" | grep -o '"reason":"[^"]*","originalExchange":"[^"]*","originalRoutingKey":"[^"]*"' | sed 's/^/  /'
check "La consulta no retira mensajes" 2 "$(dlq_ready email)"

echo "== 3. Alertas"
curl -s "$ADMIN/alerts" | grep -o '"queue":"q.cmd.email.dlq","message":"[^"]*"' | sed 's/^/  /'

echo "== 4. Reprocesar: el SMTP ya volvió, 901 se entrega; 902 vuelve a fallar"
r=$(curl -s -X POST "$ADMIN/flows/email/dlq/reprocess?count=10"); echo "  $r"
check "Movidos en la 1ª llamada" 2 "$(echo "$r" | campo moved)"
# Mailpit recién reiniciado puede tardar unos segundos en recibir
for _ in $(seq 1 15); do [ "$(asunto_en_mailpit 'Recuperado desde DLQ')" = "1" ] && break; sleep 1; done
check "Correo 901 entregado en Mailpit" 1 "$(asunto_en_mailpit 'Recuperado desde DLQ')"
check "902 volvió a la DLQ" 1 "$(dlq_ready email)"

echo "== 5. Límite de reprocesos (máx. 3): evita ciclos infinitos"
for n in 2 3; do curl -s -o /dev/null -X POST "$ADMIN/flows/email/dlq/reprocess"; esperar 3; done
r=$(curl -s -X POST "$ADMIN/flows/email/dlq/reprocess"); echo "  $r"
check "4ª llamada: 0 movidos" 0 "$(echo "$r" | campo moved)"
check "902 sigue en la DLQ para revisión manual" 1 "$(dlq_ready email)"

echo "== 6. Purgar DLQ (descartar definitivamente)"
curl -s -o /dev/null -w '  HTTP %{http_code}\n' -X DELETE "$ADMIN/flows/email/dlq/messages"
check "DLQ email vacía" 0 "$(dlq_ready email)"

echo "== 7. Flujo inválido"
check "GET /flows/xyz -> 400" 400 "$(curl -s -o /dev/null -w '%{http_code}' "$ADMIN/flows/xyz")"

echo
echo "Resultado: $ok OK, $fail FAIL"
[ "$fail" -eq 0 ]
