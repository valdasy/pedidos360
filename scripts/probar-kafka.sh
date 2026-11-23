#!/usr/bin/env bash
# =========================================================
# Pruebas de Kafka (Evaluación 3): productores, consumidores, consumer groups, DLT,
# idempotencia y administración de tópicos.
#
# Requisitos: infra/kafka (+ tópicos), infra/db, RabbitMQ, y corriendo ms-catalog (:8082),
# ms-orders (:8081), ms-audit (:8086), ms-report (:8087) y ms-kafka-admin (:8088),
# con SECURITY_ENABLED=false.
# Uso (Git Bash, desde la raíz):  bash scripts/probar-kafka.sh
# =========================================================
set -uo pipefail

ORDERS=${ORDERS_URL:-http://localhost:8081}/api/orders
CATALOG=${CATALOG_URL:-http://localhost:8082}/api/catalog
AUDIT=${AUDIT_URL:-http://localhost:8086}/api/audit
REPORT=${REPORT_URL:-http://localhost:8087}/api/report
ADMIN=${KAFKA_ADMIN_URL:-http://localhost:8088}/api/kafka-admin
J='content-type: application/json'
PY=$(command -v python3 || command -v python)

ok=0; fail=0
check() {
  if [ "$2" = "$3" ]; then echo "  [OK]   $1 (=$3)"; ok=$((ok+1));
  else echo "  [FAIL] $1 (esperado $2, obtenido $3)"; fail=$((fail+1)); fi
}
# json <expresión python sobre d>  (lee JSON de stdin)
json() { "$PY" -I -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
http() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
dlt_total() { curl -s "$ADMIN/topics/pedidos.eventos.DLT" | json "d['mensajes']"; }
lag() { curl -s "$ADMIN/groups/$1" | json "d['lagTotal']"; }
# espera hasta 30 s a que un comando devuelva el valor esperado
esperar_valor() {
  local esperado=$1; shift
  for _ in $(seq 1 30); do [ "$("$@")" = "$esperado" ] && return; sleep 1; done
}
timeline_n() { curl -s "$AUDIT/pedidos/$1/timeline" | json "len(d)"; }
timeline_tipos() { curl -s "$AUDIT/pedidos/$1/timeline" | json "','.join(sorted(e['tipo'] for e in d))"; }
kpi() { curl -s "$REPORT/kpis" | json "$1"; }

echo "== 0. Verificación previa"
for url in "$ADMIN/cluster" "$ORDERS" "$AUDIT/resumen" "$REPORT/kpis"; do
  curl -sf -o /dev/null --max-time 5 "$url" || { echo "  No responde $url"; exit 1; }
done
echo "  kafka-admin, orders, audit y report OK"

echo "== 1. Clúster y tópicos"
check "brokers en el clúster" 2 "$(curl -s "$ADMIN/cluster" | json "len(d['brokers'])")"
T=$(curl -s "$ADMIN/topics/pedidos.eventos")
check "pedidos.eventos: particiones" 3 "$(echo "$T" | json "len(d['particiones'])")"
check "pedidos.eventos: réplicas por partición" 2 "$(echo "$T" | json "len(d['particiones'][0]['replicas'])")"
check "pedidos.eventos: ISR completo en todas" True "$(echo "$T" | json "all(len(p['isr'])==2 for p in d['particiones'])")"
check "pedidos.eventos: retención 7 días" 604800000 "$(echo "$T" | json "d['configs']['retention.ms']")"
check "inventario.eventos existe" 200 "$(http "$ADMIN/topics/inventario.eventos")"
check "pedidos.eventos.DLT existe" 200 "$(http "$ADMIN/topics/pedidos.eventos.DLT")"

echo "== 2. Productor orders -> consumidores audit y report (ciclo completo)"
read -r P1 P2 <<< "$(curl -s "$CATALOG/products" | json "str(d[0]['id'])+' '+str(d[1]['id'])")"
pedidos_antes=$(kpi "d['totalPedidos']")
ID=$(curl -s -X POST "$ORDERS" -H "$J" -d "{\"clienteNombre\":\"Prueba Kafka\",\"clienteEmail\":\"kafka@prueba.cl\",\"items\":[{\"productoId\":$P1,\"cantidad\":2},{\"productoId\":$P2,\"cantidad\":1}]}" | json "d['id']")
echo "  pedido $ID creado"
for e in ACEPTADO EN_PREPARACION DESPACHADO ENTREGADO; do
  check "PATCH $e" 200 "$(http -X PATCH "$ORDERS/$ID/status" -H "$J" -d "{\"estado\":\"$e\"}")"
done
# 1 creado + 4 cambios (pedidos.eventos) + 2 STOCK_RESERVADO (inventario.eventos)
esperar_valor 7 timeline_n "$ID"
check "audit: eventos en el timeline" 7 "$(timeline_n "$ID")"
check "audit: tipos" "PEDIDO_CREADO,PEDIDO_ESTADO_CAMBIADO,PEDIDO_ESTADO_CAMBIADO,PEDIDO_ESTADO_CAMBIADO,PEDIDO_ESTADO_CAMBIADO,STOCK_RESERVADO,STOCK_RESERVADO" "$(timeline_tipos "$ID")"
check "audit: los 5 eventos del pedido en la MISMA partición (clave=pedidoId)" 1 \
  "$(curl -s "$AUDIT/pedidos/$ID/timeline" | json "len({e['particion'] for e in d if e['topico']=='pedidos.eventos'})")"
esperar_valor $((pedidos_antes+1)) kpi "d['totalPedidos']"
check "report: total de pedidos +1" $((pedidos_antes+1)) "$(kpi "d['totalPedidos']")"
check "report: top productos incluye el producto $P1" True "$(curl -s "$REPORT/top-productos?limit=50" | json "any(p['productoId']==$P1 for p in d)")"

echo "== 3. Cancelación: PEDIDO_CANCELADO + STOCK_LIBERADO"
cancel_antes=$(kpi "d['cancelados']")
ID2=$(curl -s -X POST "$ORDERS" -H "$J" -d "{\"clienteNombre\":\"Prueba Cancela\",\"clienteEmail\":\"kafka@prueba.cl\",\"items\":[{\"productoId\":$P1,\"cantidad\":1}]}" | json "d['id']")
http -X PATCH "$ORDERS/$ID2/status" -H "$J" -d '{"estado":"ACEPTADO"}' > /dev/null
check "PATCH CANCELADO" 200 "$(http -X PATCH "$ORDERS/$ID2/status" -H "$J" -d '{"estado":"CANCELADO"}')"
esperar_valor 5 timeline_n "$ID2"
check "audit: tipos del pedido cancelado" "PEDIDO_CANCELADO,PEDIDO_CREADO,PEDIDO_ESTADO_CAMBIADO,STOCK_LIBERADO,STOCK_RESERVADO" "$(timeline_tipos "$ID2")"
esperar_valor $((cancel_antes+1)) kpi "d['cancelados']"
check "report: cancelados +1" $((cancel_antes+1)) "$(kpi "d['cancelados']")"

echo "== 4. Idempotencia: el mismo evento entregado dos veces"
procesados_antes=$(kpi "d['eventosProcesados']")
EVENTO=$(curl -s "$ADMIN/topics/pedidos.eventos/messages?limit=50" | json "[m['valor'] for m in d if '\"pedidoId\":$ID,' in m['valor'] and 'PEDIDO_CREADO' in m['valor']][0]")
"$PY" -I -c "import json,sys; print(json.dumps({'clave':sys.argv[1],'valor':sys.argv[2]}))" "$ID" "$EVENTO" > /tmp/p360-dup.json
check "republicar PEDIDO_CREADO" 201 "$(http -X POST "$ADMIN/topics/pedidos.eventos/messages" -H "$J" --data-binary @/tmp/p360-dup.json)"
esperar_valor 0 lag pedidos360-report; esperar_valor 0 lag pedidos360-audit
check "audit: el timeline NO creció" 7 "$(timeline_n "$ID")"
check "report: eventos procesados sin cambio" "$procesados_antes" "$(kpi "d['eventosProcesados']")"

echo "== 5. JSON mal formado -> DLT sin bloquear la partición (un DLT por consumer group)"
dlt0=$(dlt_total)
check "publicar basura" 201 "$(http -X POST "$ADMIN/topics/pedidos.eventos/messages" -H "$J" -d '{"clave":"x","valor":"esto no es json {"}')"
esperar_valor $((dlt0+2)) dlt_total
check "DLT +2 (audit y report)" $((dlt0+2)) "$(dlt_total)"

echo "== 6. Evento inválido (sin pedidoId) -> DLT sin reintentos"
dlt0=$(dlt_total)
check "publicar evento inválido" 201 "$(http -X POST "$ADMIN/topics/pedidos.eventos/messages" -H "$J" \
  -d '{"clave":"y","valor":"{\"eventId\":\"33333333-3333-3333-3333-333333333333\",\"tipo\":\"PEDIDO_CREADO\",\"estado\":\"CREADO\",\"total\":10,\"ocurridoEn\":\"2026-01-01T00:00:00Z\"}"}')"
esperar_valor $((dlt0+2)) dlt_total
check "DLT +2" $((dlt0+2)) "$(dlt_total)"
check "cabecera del DLT explica el error" True "$(curl -s "$ADMIN/topics/pedidos.eventos.DLT/messages?limit=5" | json "any('pedidoId' in (m['cabeceras'].get('kafka_dlt-exception-message') or '') for m in d)")"
echo "  ...y los consumidores siguen procesando:"
esperar_valor 0 lag pedidos360-audit
check "lag pedidos360-audit" 0 "$(lag pedidos360-audit)"
check "lag pedidos360-report" 0 "$(lag pedidos360-report)"

echo "== 7. Consumer groups"
G=$(curl -s "$ADMIN/groups")
check "grupo pedidos360-audit STABLE" STABLE "$(echo "$G" | json "[g['estado'] for g in d if g['grupoId']=='pedidos360-audit'][0]")"
check "grupo pedidos360-report STABLE" STABLE "$(echo "$G" | json "[g['estado'] for g in d if g['grupoId']=='pedidos360-report'][0]")"
check "audit tiene asignadas las 3 particiones de pedidos.eventos" 3 \
  "$(curl -s "$ADMIN/groups/pedidos360-audit" | json "sum(1 for m in d['miembros'] for p in m['particiones'] if p.startswith('pedidos.eventos-'))")"

echo "== 8. Administración de tópicos (ms-kafka-admin)"
TMP=prueba.admin.$RANDOM
check "crear $TMP (2 particiones, réplica 2)" 201 "$(http -X POST "$ADMIN/topics" -H "$J" -d "{\"nombre\":\"$TMP\",\"particiones\":2,\"replicas\":2,\"configs\":{\"retention.ms\":\"3600000\"}}")"
check "crear duplicado -> 409" 409 "$(http -X POST "$ADMIN/topics" -H "$J" -d "{\"nombre\":\"$TMP\",\"particiones\":1,\"replicas\":1}")"
check "réplica 3 con 2 brokers -> 400" 400 "$(http -X POST "$ADMIN/topics" -H "$J" -d '{"nombre":"prueba.r3","particiones":1,"replicas":3}')"
check "modificar retention.ms" 86400000 "$(curl -s -X PATCH "$ADMIN/topics/$TMP/configs" -H "$J" -d '{"configs":{"retention.ms":"86400000"}}' | json "d['configs']['retention.ms']")"
check "aumentar particiones a 4" 4 "$(curl -s -X POST "$ADMIN/topics/$TMP/partitions" -H "$J" -d '{"total":4}' | json "len(d['particiones'])")"
check "reducir particiones -> 400" 400 "$(http -X POST "$ADMIN/topics/$TMP/partitions" -H "$J" -d '{"total":2}')"
check "eliminar" 204 "$(http -X DELETE "$ADMIN/topics/$TMP")"
esperar_valor 404 http "$ADMIN/topics/$TMP"
check "ya no existe" 404 "$(http "$ADMIN/topics/$TMP")"

echo
echo "Resultado: $ok OK, $fail FAIL"
[ "$fail" -eq 0 ]
