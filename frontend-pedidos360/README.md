# frontend-pedidos360

SPA en **React + TypeScript + Vite** con login de **Microsoft Entra ID** mediante **MSAL**
(`@azure/msal-browser` + `@azure/msal-react`), como en el tutorial del curso.

## Ejecutar

```bash
cp .env.example .env.local   # y completar
npm install
npm run dev                  # http://localhost:5173
```

Los microservicios deben estar corriendo: Vite reenvía `/api/orders`, `/api/catalog` y
`/api/mq-admin` a los puertos 8081, 8082 y 8085 (`vite.config.ts`), así no hay CORS en local.

## Dos modos de autenticación (`VITE_AUTH_MODE`)

| Modo | Login | Backend |
|---|---|---|
| `practica` | Eliges el rol en pantalla. Sin Azure | `SECURITY_ENABLED=false` |
| `entra` | Botón "Iniciar sesión con Microsoft" (MSAL, Authorization Code + PKCE) | `SECURITY_ENABLED=true` |

En modo práctica el backend no recibe token, así que no puede filtrar "mis pedidos":
un Cliente ve todos. Con Entra activado, la API filtra por el email del token.

## Pantallas (rutas del caso)

| Ruta | Roles | Contenido |
|---|---|---|
| `/login` | público | Login |
| `/dashboard` | todos | Admin: KPIs (ventas, lead time, alertas). Operador: pendientes y en curso. Cliente: últimos pedidos |
| `/orders` | todos | Listado filtrable, detalle con historial, botones de estado según `siguientesEstados`, crear pedido (Cliente/Operador) |
| `/catalog` | Admin, Operador | Productos. Solo Admin crea/edita/elimina |
| `/admin/mq` | Admin | Colas, DLQ, reproceso, purga, alertas (ms-mq-admin) |

## Cómo viaja el token

1. `loginRedirect` con el scope de la API (`VITE_API_SCOPE`).
2. `acquireTokenSilent` obtiene el access token (de caché o renovándolo).
3. `apiFetch` lo envía como `Authorization: Bearer <token>`.
4. API Gateway valida el JWT y lo reenvía; cada microservicio lo vuelve a validar y aplica roles.

Los **roles** (`Admin`, `Operador`, `Cliente`) están en el claim `roles` del **access token**,
porque los App roles se definen en el registro de la API. El frontend lo decodifica solo
para mostrar u ocultar menús; la seguridad real la aplica el backend.

## Estructura

```
src/
  config.ts              variables VITE_*
  auth/msal.ts           msalConfig + PublicClientApplication (como authConfig.ts del tutorial)
  auth/AuthContext.tsx   useAuth(): usuario, roles, login, logout, getToken (modo entra y práctica)
  api/client.ts          fetch con Bearer token y errores Problem Details
  api/useApi.ts          funciones de orders, catalog y mq-admin
  components/            Layout (menú por rol), RequireRole (guard), StatusBadge
  pages/                 Login, Dashboard, Orders, Catalog, AdminMq
```
