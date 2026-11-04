#!/usr/bin/env bash
# =========================================================
# Pruebas del clúster RabbitMQ de 2 nodos con el sistema completo:
# se apaga un nodo a la vez y se comprueba qué sigue funcionando.
#
# Requisitos: infra/mq-cluster (2 nodos + Mailpit) con topología cargada, infra/db,
# ms-catalog, ms-orders, ms-notify y ms-mq-admin corriendo.
# Uso (Git Bash, desde la raíz):  bash scripts/probar-cluster.sh
#                                 ORDERS_URL=http://localhost:8091 bash scripts/probar-cluster.sh
# Tarda ~3-4 minutos (espera a que los nodos se detengan y vuelvan).
# =========================================================
set -uo pipefail

BASE_ORDERS=${ORDERS_URL:-http://localhost:8081}
ORDERS=$BASE_ORDERS/api/orders
ADMIN=http://localhost:8085/api/mq-admin
MAILPIT=http://localhost:8025/api/v1
J='content-type: application/json'

ok=0; fail=0
check() {
  if [ "$2" = "$3" ]; then echo "  [OK]   $1 (=$3)"; ok=$((ok+1));
  else echo "  [FAIL] $1 (esperado $2, obtenido $3)"; fail=$((fail+1)); fi
}

# rabbitmqctl en cualquier nodo que esté arriba
rq() {
  for n in pedidos360-rabbit1 pedidos360-rabbit2; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$n" 2>/dev/null)" = true ]; then
      docker exec "$n" rabbitmqctl -q "$@" 2>/dev/null | tr -d '\r'; return
    fi
  done
}
consumidores() { rq list_queues name consumers | awk -v q="$1" '$1==q {print $2}'; }
lider()        { rq list_queues name leader    | awk -v q="$1" '$1==q {print $2}'; }
esperar_sano() {
  for _ in $(seq 1 60); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$1")" = healthy ] && return; sleep 3; done
}
esperar_consumidor() {  # esperar_consumidor <cola> : hasta que notify vuelva a consumir
  for _ in $(seq 1 30); do [ "${consumidores_val:=$(consumidores "$1")}" -ge 1 ] 2>/dev/null && return; consumidores_val=; sleep 2; done
}
asunto() {  # asunto <texto> -> cantidad de correos cuyo asunto contiene el texto
  sleep 1; curl -s "$MAILPIT/messages?limit=300" | grep -o "\"Subject\":\"[^\"]*$1[^\"]*\"" | wc -l | tr -d ' '
}
esperar_asunto() { for _ in $(seq 1 20); do [ "$(asunto "$1")" -ge 1 ] && return; sleep 1; done; }
nuevo_pedido() {
  curl -s -X POST "$ORDERS" -H "$J" -d "{\"clienteNombre\":\"$1\",\"clienteEmail\":\"$2\",\"items\":[{\"productoId\":2,\"cantidad\":1}]}" \
    | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2
}
estado() { curl -s -o /dev/null -w '%{http_code}' -X PATCH "$ORDERS/$1/status" -H "$J" -d "{\"estado\":\"$2\"}"; }
nc() { printf 'NC-%06d' "$1"; }

echo "== 0. Estado inicial del clúster"
corriendo=$(rq cluster_status | sed -n '/Running Nodes/,/Versions/p' | grep -c '^rabbit@')
check "nodos corriendo" 2 "$corriendo"
for q in email kitchen invoice; do echo "  q.cmd.$q vive en $(lider "q.cmd.$q")"; done
NODO_EMAIL=$(lider q.cmd.email); NODO_INVOICE=$(lider q.cmd.invoice)
if [ "$NODO_EMAIL" = "$NODO_INVOICE" ]; then
  echo "  AVISO: email e invoice viven en el mismo nodo; esta prueba espera que estén repartidas."
  echo "  (Recrear: docker compose down -v && up -d && run --rm topology-init en infra/mq-cluster)"
fi
contenedor() { echo "pedidos360-${1#rabbit@}"; }   # rabbit@rabbit1 -> pedidos360-rabbit1

# ---------------------------------------------------------------------------
echo "== 1. Cae el nodo de q.cmd.invoice ($NODO_INVOICE)"
docker stop "$(contenedor "$NODO_INVOICE")" > /dev/null; sleep 8
alertas=$(curl -s --max-time 20 "$ADMIN/alerts")
check "mq-admin alerta 'Nodo del clúster caído'" 1 "$(echo "$alertas" | grep -c 'Nodo del cl')"
check "mq-admin alerta q.cmd.invoice no disponible" 1 "$(echo "$alertas" | grep -o '"queue":"q.cmd.invoice","message":"Cola no disponible' | wc -l | tr -d ' ')"
ID=$(nuevo_pedido "Cluster Uno" "cluster1@ejemplo.cl")
check "orders sigue creando pedidos" 1 "$([ -n "$ID" ] && echo 1)"
check "cancelar el pedido #$ID" 200 "$(estado "$ID" CANCELADO)"
esperar_asunto "pedido #$ID fue cancelado"
check "correo de cancelación (cola en el otro nodo) llega" 1 "$(asunto "pedido #$ID fue cancelado")"
sleep 3
check "nota de crédito NO llega (su cola está caída; orders lo registra)" 0 "$(asunto "$(nc "$ID")")"

echo "   ...vuelve $NODO_INVOICE"
docker start "$(contenedor "$NODO_INVOICE")" > /dev/null; esperar_sano "$(contenedor "$NODO_INVOICE")"
esperar_consumidor q.cmd.invoice
check "notify vuelve a consumir q.cmd.invoice" 1 "$(consumidores q.cmd.invoice)"

# ---------------------------------------------------------------------------
echo "== 2. Cae el nodo de q.cmd.email ($NODO_EMAIL), donde están conectados los servicios"
docker stop "$(contenedor "$NODO_EMAIL")" > /dev/null; sleep 15
overview=$(curl -s --max-time 20 "$ADMIN/overview")
check "mq-admin responde vía el otro nodo (failover)" 1 "$(echo "$overview" | grep -c "\"name\":\"$NODO_EMAIL\",\"running\":false")"
ID2=$(nuevo_pedido "Cluster Dos" "cluster2@ejemplo.cl")
check "orders se reconectó y sigue creando pedidos" 1 "$([ -n "$ID2" ] && echo 1)"
check "cancelar el pedido #$ID2" 200 "$(estado "$ID2" CANCELADO)"
esperar_asunto "$(nc "$ID2")"
check "nota de crédito (cola en el nodo vivo) llega aunque el correo falle" 1 "$(asunto "$(nc "$ID2")")"
check "correo de cancelación NO llega (su cola está caída)" 0 "$(asunto "pedido #$ID2 fue cancelado")"

echo "   ...vuelve $NODO_EMAIL"
docker start "$(contenedor "$NODO_EMAIL")" > /dev/null; esperar_sano "$(contenedor "$NODO_EMAIL")"
esperar_consumidor q.cmd.email
check "notify vuelve a consumir q.cmd.email (missing-queues-fatal=false)" 1 "$(consumidores q.cmd.email)"

# ---------------------------------------------------------------------------
echo "== 3. Clúster recuperado: todo funciona de nuevo"
check "nodos corriendo" 2 "$(rq cluster_status | sed -n '/Running Nodes/,/Versions/p' | grep -c '^rabbit@')"
ID3=$(nuevo_pedido "Cluster Tres" "cluster3@ejemplo.cl")
estado "$ID3" CANCELADO > /dev/null
esperar_asunto "$(nc "$ID3")"; esperar_asunto "pedido #$ID3 fue cancelado"
check "correo de cancelación llega" 1 "$(asunto "pedido #$ID3 fue cancelado")"
check "nota de crédito llega" 1 "$(asunto "$(nc "$ID3")")"

echo
echo "Resultado: $ok OK, $fail FAIL"
echo "Los comandos que no se pudieron publicar quedan en el log de orders como ERROR"
echo "(patrón Transactional Outbox para reintentarlos: ver docs/guia-estudio.md)."
[ "$fail" -eq 0 ]
