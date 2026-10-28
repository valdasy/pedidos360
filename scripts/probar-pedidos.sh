#!/usr/bin/env bash
# =========================================================
# Pruebas del ciclo de vida del pedido (Etapa 4):
# orders (Oracle) <-> catalog (Oracle, stock) y comandos RabbitMQ -> notify -> Mailpit
#
# Requisitos: infra/mq e infra/db levantados; ms-catalog (:8082), ms-orders (:8081)
# y ms-notify corriendo.   Uso (Git Bash, desde la raíz): bash scripts/probar-pedidos.sh
# =========================================================
set -uo pipefail

ORDERS=http://localhost:8081/api/orders
CATALOG=http://localhost:8082/api/catalog
MAILPIT=http://localhost:8025/api/v1
J='content-type: application/json'
RESP=/tmp/p360-pedidos.json

# req <método> <url> [json] -> imprime el código HTTP; el cuerpo queda en $RESP
req() {
  if [ $# -ge 3 ]; then curl -s -o "$RESP" -w '%{http_code}' -X "$1" "$2" -H "$J" -d "$3";
  else curl -s -o "$RESP" -w '%{http_code}' -X "$1" "$2"; fi
}
campo()   { grep -o "\"$1\":\"\\?[^,\"]*" "$RESP" | head -1 | sed 's/.*:"\{0,1\}//'; }
estado()  { req PATCH "$ORDERS/$1/status" "{\"estado\":\"$2\"}"; }
stock()   { curl -s "$CATALOG/products/$1" | grep -o '"stock":[0-9]*' | cut -d: -f2; }
correos_pedido() {  # cantidad de correos cuyo asunto menciona el pedido #id
  sleep 1
  curl -s "$MAILPIT/messages?limit=200" | grep -oE "\"Subject\":\"[^\"]*#$1( |\")" | wc -l | tr -d ' '
}
esperar_correos() {  # esperar_correos <id> <cantidad>
  for _ in $(seq 1 20); do [ "$(correos_pedido "$1")" -ge "$2" ] && return; sleep 1; done
}

ok=0; fail=0
check() {
  if [ "$2" = "$3" ]; then echo "  [OK]   $1 (=$3)"; ok=$((ok+1));
  else echo "  [FAIL] $1 (esperado $2, obtenido $3)"; fail=$((fail+1)); cat "$RESP" 2>/dev/null | head -c 300; echo; fi
}

echo "== Verificación previa"
for url in "$CATALOG/products" "http://localhost:8081/actuator/health"; do
  curl -sf -o /dev/null --max-time 3 "$url" || { echo "  No responde $url"; exit 1; }
done
echo "  catalog y orders OK"

CAFE_ANTES=$(stock 1); MEDIA_ANTES=$(stock 3); JUGO_ANTES=$(stock 6)

echo "== 1. Crear pedido: 2 cafés (2.500) + 1 medialuna (1.200)"
check "POST /orders -> 201" 201 "$(req POST "$ORDERS" '{"clienteNombre":"Ana Perez","clienteEmail":"ana@ejemplo.cl","items":[{"productoId":1,"cantidad":2},{"productoId":3,"cantidad":1}]}')"
ID=$(campo id)
check "estado" CREADO "$(campo estado)"
check "total calculado con precios del catálogo" 6200 "$(campo total)"
check "crear NO descuenta stock" "$CAFE_ANTES" "$(stock 1)"

echo "== 2. Regla del caso: no se puede despachar sin aceptar"
check "CREADO -> DESPACHADO -> 409" 409 "$(estado "$ID" DESPACHADO)"

echo "== 3. Aceptar: descuenta stock en ms-catalog"
check "CREADO -> ACEPTADO -> 200" 200 "$(estado "$ID" ACEPTADO)"
check "stock café" $((CAFE_ANTES - 2)) "$(stock 1)"
check "stock medialuna" $((MEDIA_ANTES - 1)) "$(stock 3)"
check "editar pedido ACEPTADO -> 409" 409 "$(req PUT "$ORDERS/$ID" '{"clienteNombre":"X","clienteEmail":"x@x.cl","items":[{"productoId":1,"cantidad":1}]}')"

echo "== 4. Resto del ciclo"
check "ACEPTADO -> EN_PREPARACION" 200 "$(estado "$ID" EN_PREPARACION)"
check "EN_PREPARACION -> DESPACHADO" 200 "$(estado "$ID" DESPACHADO)"
check "DESPACHADO -> CANCELADO -> 409 (ya salió)" 409 "$(estado "$ID" CANCELADO)"
check "DESPACHADO -> ENTREGADO" 200 "$(estado "$ID" ENTREGADO)"
check "entregadoEn registrado" 1 "$(grep -c '"entregadoEn":"20' "$RESP")"
check "historial con 5 estados" 5 "$(curl -s "$ORDERS/$ID/history" | grep -o estadoNuevo | wc -l | tr -d ' ')"

echo "== 5. Comandos RabbitMQ -> notify: un correo por cada estado"
esperar_correos "$ID" 5
check "correos del pedido #$ID en Mailpit" 5 "$(correos_pedido "$ID")"

echo "== 6. Stock insuficiente: el pedido no se acepta y el stock no cambia"
req POST "$ORDERS" '{"clienteNombre":"Bruno","clienteEmail":"bruno@ejemplo.cl","items":[{"productoId":1,"cantidad":1},{"productoId":6,"cantidad":50}]}' > /dev/null
ID2=$(campo id)
check "ACEPTADO con 50 jugos -> 409" 409 "$(estado "$ID2" ACEPTADO)"
check "detalle del producto faltante" 1 "$(grep -c '"nombre":"Jugo natural"' "$RESP")"
check "pedido sigue CREADO" CREADO "$(req GET "$ORDERS/$ID2" > /dev/null; campo estado)"
check "stock jugo sin cambios" "$JUGO_ANTES" "$(stock 6)"
check "stock café sin cambios (todo o nada)" $((CAFE_ANTES - 2)) "$(stock 1)"
check "eliminar pedido CREADO -> 204" 204 "$(req DELETE "$ORDERS/$ID2")"

echo "== 7. Cancelar un pedido aceptado devuelve el stock"
req POST "$ORDERS" '{"clienteNombre":"Carla","clienteEmail":"carla@ejemplo.cl","items":[{"productoId":1,"cantidad":3}]}' > /dev/null
ID3=$(campo id)
estado "$ID3" ACEPTADO > /dev/null
check "stock café tras aceptar" $((CAFE_ANTES - 5)) "$(stock 1)"
check "ACEPTADO -> CANCELADO" 200 "$(estado "$ID3" CANCELADO)"
check "stock café devuelto" $((CAFE_ANTES - 2)) "$(stock 1)"
check "CANCELADO es final -> 409" 409 "$(estado "$ID3" ACEPTADO)"

echo "== 8. Validaciones"
check "producto inexistente -> 404" 404 "$(req POST "$ORDERS" '{"clienteNombre":"D","clienteEmail":"d@ejemplo.cl","items":[{"productoId":9999,"cantidad":1}]}')"
check "email inválido -> 400" 400 "$(req POST "$ORDERS" '{"clienteNombre":"D","clienteEmail":"no-es-email","items":[{"productoId":1,"cantidad":1}]}')"
check "pedido inexistente -> 404" 404 "$(req GET "$ORDERS/999999")"

echo
echo "Resultado: $ok OK, $fail FAIL"
echo "Revisa Mailpit (http://localhost:8025): correos del pedido #$ID"
[ "$fail" -eq 0 ]
