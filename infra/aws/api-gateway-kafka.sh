#!/usr/bin/env bash
# =========================================================
# Agrega al API Gateway YA CREADO (api-gateway.sh) las rutas de la Evaluación 3:
#   /api/audit/**        -> EC2 apps :8086  (ms-audit)
#   /api/report/**       -> EC2 apps :8087  (ms-report)
#   /api/kafka-admin/**  -> EC2 apps :8088  (ms-kafka-admin)
# Mismo JWT authorizer de Entra. Idempotente: no duplica rutas existentes.
#
# Ejecutar en AWS CloudShell:
#   cd ~/pedidos360 && git pull && cd infra/aws
#   EC2_APPS_HOST=<Elastic IP> bash api-gateway-kafka.sh
# =========================================================
set -euo pipefail

: "${EC2_APPS_HOST:?Define EC2_APPS_HOST=<Elastic IP de ec2-apps>}"
API_NAME="pedidos360-api"

API_ID=$(aws apigatewayv2 get-apis --query "Items[?Name=='${API_NAME}'].ApiId | [0]" --output text)
[ "$API_ID" = "None" ] && { echo "No existe el API ${API_NAME}: ejecuta primero api-gateway.sh"; exit 1; }
AUTH_ID=$(aws apigatewayv2 get-authorizers --api-id "$API_ID" --query "Items[?Name=='entra-jwt'].AuthorizerId | [0]" --output text)
echo "API ${API_ID} · authorizer ${AUTH_ID}"

EXISTENTES=$(aws apigatewayv2 get-routes --api-id "$API_ID" --query "Items[].RouteKey" --output text)

integration() {
  aws apigatewayv2 create-integration --api-id "$API_ID" --integration-type HTTP_PROXY \
    --integration-method ANY --integration-uri "$1" --payload-format-version 1.0 \
    --query IntegrationId --output text
}

route() {
  if grep -qF "$1" <<< "$EXISTENTES"; then echo "   ya existe: $1"; return; fi
  aws apigatewayv2 create-route --api-id "$API_ID" --route-key "$1" --target "integrations/$2" \
    --authorization-type JWT --authorizer-id "$AUTH_ID" > /dev/null
  echo "   ruta $1"
}

for svc in "audit:8086" "report:8087" "kafka-admin:8088"; do
  name=${svc%%:*}; port=${svc##*:}
  if grep -qF "GET /api/${name}/{proxy+}" <<< "$EXISTENTES"; then
    echo "== ${name}: rutas ya creadas"; continue
  fi
  echo "== ${name} -> ${EC2_APPS_HOST}:${port}"
  base=$(integration "http://${EC2_APPS_HOST}:${port}/api/${name}")
  proxy=$(integration "http://${EC2_APPS_HOST}:${port}/api/${name}/{proxy}")
  for m in GET POST; do route "$m /api/${name}" "$base"; done
  for m in GET POST PUT PATCH DELETE; do route "$m /api/${name}/{proxy+}" "$proxy"; done
done

URL="https://${API_ID}.execute-api.$(aws configure get region || echo us-east-1).amazonaws.com"
echo
echo "Listo (el stage \$default tiene auto-deploy). Prueba: curl -i ${URL}/api/report/kpis -> 401"
