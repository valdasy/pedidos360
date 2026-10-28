# Etapa 7 — API Manager: AWS API Gateway con JWT de Entra ID (IL 1.1)

**Requisitos:** Etapa 5 lista (tenant ID y client ID de `pedidos360-api`) y la EC2 de apps
funcionando con su Elastic IP (Etapa 8, pasos 1 a 5). En la práctica: primero despliega,
después crea el gateway.

## Qué hace API Gateway en Pedidos360

```
Navegador ──Bearer JWT──► API Gateway (HTTP API)
                            1. CORS: ¿el origen está permitido?
                            2. Throttling: ¿supera 50 req/s?            -> 429
                            3. JWT Authorizer: firma, exp, iss, aud     -> 401 si falla
                            4. Ruta -> integración
                          ──► EC2 apps :8081 orders | :8082 catalog | :8085 mq-admin
                                5. Spring Security valida el JWT OTRA VEZ + roles -> 403
```

| Función del API Manager (unidad 1) | Cómo se cumple |
|---|---|
| **Gestionar** | Un solo punto de entrada (una URL) para 3 microservicios |
| **Escalar** | Servicio administrado de AWS; throttling protege a los backends |
| **Filtrar** | Rutas explícitas por método; lo que no está definido responde 404 en el gateway |
| **Autenticar** | JWT Authorizer con issuer/audience de Entra ID |
| **Securizar** | HTTPS automático (`*.execute-api.amazonaws.com`), CORS, sin token no pasa |

**¿Por qué validar dos veces (gateway y Spring)?** *Defensa en profundidad*: los puertos de
la EC2 también son alcanzables directamente; si alguien se salta el gateway, Spring rechaza
igual. Además, el gateway **no conoce roles**: Spring decide si un Cliente puede aceptar un
pedido (no puede).

## Opción A: con el script (recomendada)

1. Learner Lab → **Start Lab** → **AWS** (consola).
2. Icono de **CloudShell** (terminal `>_` arriba).
3. **Actions → Upload file** → `infra/aws/api-gateway.sh`.
4. Editar las 4 variables:

```bash
nano api-gateway.sh
```

5. Ejecutar:

```bash
bash api-gateway.sh
```

6. Anotar la URL que imprime al final.

## Opción B: en la consola (para entender cada pieza)

1. **API Gateway → Create API → HTTP API → Build**. Name `pedidos360-api` → Next (sin integraciones aún).
2. **Authorization → Manage authorizers → Create**:
   - Type **JWT**, name `entra-jwt`
   - Identity source: `$request.header.Authorization`
   - Issuer URL: `https://login.microsoftonline.com/<TENANT_ID>/v2.0`
   - Audience: `<API_ID>` y también `api://<API_ID>`
3. **Integrations → Create**: tipo **HTTP URI**, método ANY,
   URL `http://<EIP>:8081/api/orders/{proxy}` (repetir para catalog 8082 y mq-admin 8085,
   y una versión sin `/{proxy}` para la ruta base).
4. **Routes → Create**: `GET /api/orders`, `POST /api/orders`, `GET /api/orders/{proxy+}`,
   `PATCH /api/orders/{proxy+}`, etc. Para cada ruta: **Attach integration** y
   **Attach authorizer** `entra-jwt`.
5. **CORS**: Allow origins `http://localhost:5173` (y la URL de CloudFront), methods
   GET POST PUT PATCH DELETE OPTIONS, headers `authorization, content-type`.
6. **Stages → $default**: auto-deploy activado. **Throttling**: burst 100, rate 50.

> No uses rutas `ANY`: capturan también `OPTIONS` y el *preflight* de CORS fallaría con 401,
> porque el navegador no envía el token en el preflight.

## Probar

```bash
curl -i https://<id>.execute-api.us-east-1.amazonaws.com/api/orders
```

Sin token responde **401** `{"message":"Unauthorized"}` (lo responde API Gateway; el
microservicio ni se entera).

Con token (botón **Copiar token** del frontend):

```bash
curl -i -H "Authorization: Bearer <token>" https://<id>.execute-api.us-east-1.amazonaws.com/api/orders
```

| Caso | Respuesta | Quién responde |
|---|---|---|
| Sin token / token vencido / de otro tenant | 401 | API Gateway |
| Token válido, rol insuficiente (Cliente → `/api/mq-admin/alerts`) | 403 | Spring Security |
| Token válido y rol correcto | 200 | Microservicio |
| Más de 50 req/s sostenidas | 429 | API Gateway |

Para el frontend: en `.env.local`, `VITE_API_BASE_URL=https://<id>.execute-api.us-east-1.amazonaws.com`
y reiniciar `npm run dev`.

## Problemas frecuentes

| Síntoma | Causa |
|---|---|
| 401 con token recién copiado | `iss` o `aud` no coinciden: revisa en jwt.ms y en el authorizer (tokens v2, Etapa 5 paso 1.3) |
| Error CORS en el navegador | Falta el origen en *Allow origins* o se usaron rutas ANY |
| 503 / 504 | La EC2 está apagada, el contenedor no corre o el Security Group no abre 8081/8082/8085 |
| Funcionaba ayer y hoy no | Se reinició el lab y cambió la IP de la EC2: usar **Elastic IP** |
