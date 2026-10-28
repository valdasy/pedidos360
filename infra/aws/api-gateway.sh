#!/usr/bin/env bash
# =========================================================
# Crea el API Manager: AWS API Gateway (HTTP API) con JWT Authorizer de Entra ID.
#
# Ejecutar en AWS CloudShell (ya tiene las credenciales del Learner Lab):
#   1. Subir este archivo (Actions -> Upload file) o pegarlo con un editor.
#   2. Editar las 4 variables de abajo.
#   3. bash api-gateway.sh
#
# Resultado: https://<id>.execute-api.us-east-1.amazonaws.com
#   /api/orders/**    -> EC2 apps :8081
#   /api/catalog/**   -> EC2 apps :8082
#   /api/mq-admin/**  -> EC2 apps :8085
# Todas las rutas exigen "Authorization: Bearer <JWT de Entra>" válido.
# =========================================================
set -euo pipefail

# ---------- EDITAR ----------
EC2_APPS_HOST="1.2.3.4"                                 # Elastic IP (o DNS público) de la EC2 apps
ENTRA_TENANT_ID="00000000-0000-0000-0000-000000000000"
ENTRA_API_CLIENT_ID="00000000-0000-0000-0000-000000000000"  # client ID de pedidos360-api
FRONTEND_ORIGINS="http://localhost:5173"                # + ",https://xxxx.cloudfront.net" cuando exista
# ----------------------------

API_NAME="pedidos360-api"
ISSUER="https://login.microsoftonline.com/${ENTRA_TENANT_ID}/v2.0"
# "http://a,https://b" -> "\"http://a\",\"https://b\"" (lista JSON)
ORIGINS_JSON=$(echo "$FRONTEND_ORIGINS" | sed 's/[^,][^,]*/"&"/g')

echo "== 1. Crear HTTP API con CORS"
API_ID=$(aws apigatewayv2 create-api \
  --name "$API_NAME" \
  --protocol-type HTTP \
  --cors-configuration "{\"AllowOrigins\":[${ORIGINS_JSON}],\"AllowMethods\":[\"GET\",\"POST\",\"PUT\",\"PATCH\",\"DELETE\",\"OPTIONS\"],\"AllowHeaders\":[\"authorization\",\"content-type\",\"x-event-id\"],\"MaxAge\":3600}" \
  --query ApiId --output text)
echo "   ApiId: $API_ID"

echo "== 2. JWT Authorizer (Entra ID)"
# API Gateway descarga las claves públicas desde ${ISSUER}/.well-known/openid-configuration
# y valida firma, expiración, issuer y audience ANTES de llegar a los microservicios.
AUTH_ID=$(aws apigatewayv2 create-authorizer \
  --api-id "$API_ID" \
  --name entra-jwt \
  --authorizer-type JWT \
  --identity-source '$request.header.Authorization' \
  --jwt-configuration "{\"Audience\":[\"${ENTRA_API_CLIENT_ID}\",\"api://${ENTRA_API_CLIENT_ID}\"],\"Issuer\":\"${ISSUER}\"}" \
  --query AuthorizerId --output text)
echo "   AuthorizerId: $AUTH_ID"

# integración HTTP_PROXY: reenvía método, path, query string, headers (incluido Authorization) y body
integration() {
  aws apigatewayv2 create-integration \
    --api-id "$API_ID" \
    --integration-type HTTP_PROXY \
    --integration-method ANY \
    --integration-uri "$1" \
    --payload-format-version 1.0 \
    --query IntegrationId --output text
}

route() {  # route "<MÉTODO> <path>" <integrationId>
  aws apigatewayv2 create-route \
    --api-id "$API_ID" \
    --route-key "$1" \
    --target "integrations/$2" \
    --authorization-type JWT \
    --authorizer-id "$AUTH_ID" > /dev/null
  echo "   ruta $1"
}

echo "== 3. Rutas e integraciones"
# Se crean rutas por método (no ANY) para que las peticiones OPTIONS (preflight CORS)
# no exijan token: las responde API Gateway con la configuración CORS.
for svc in "orders:8081" "catalog:8082" "mq-admin:8085"; do
  name=${svc%%:*}; port=${svc##*:}
  base=$(integration "http://${EC2_APPS_HOST}:${port}/api/${name}")
  proxy=$(integration "http://${EC2_APPS_HOST}:${port}/api/${name}/{proxy}")
  for m in GET POST; do route "$m /api/${name}" "$base"; done
  for m in GET POST PUT PATCH DELETE; do route "$m /api/${name}/{proxy+}" "$proxy"; done
done

echo "== 4. Stage \$default con auto-deploy y límite de tráfico (throttling)"
# Throttling: máximo 50 peticiones/segundo sostenidas y ráfagas de 100. Sobre eso: 429.
aws apigatewayv2 create-stage \
  --api-id "$API_ID" \
  --stage-name '$default' \
  --auto-deploy \
  --default-route-settings '{"ThrottlingBurstLimit":100,"ThrottlingRateLimit":50}' > /dev/null

URL="https://${API_ID}.execute-api.$(aws configure get region || echo us-east-1).amazonaws.com"
echo
echo "Listo. URL de la API: $URL"
echo "  - Sin token:  curl -i $URL/api/orders            -> 401"
echo "  - Con token:  curl -H \"Authorization: Bearer <token>\" $URL/api/orders -> 200"
echo "Pon esta URL en frontend-pedidos360/.env.local como VITE_API_BASE_URL"
