# Etapa 5 — Configurar Microsoft Entra ID (IL 1.2)

Guía para hacer **con tu cuenta** en https://portal.azure.com (unos 30-40 minutos).
Al terminar, el frontend inicia sesión con Microsoft y los microservicios validan el token
y los roles.

## Qué vamos a crear

```
Tenant (directorio)
├── App registration "pedidos360-api"   ← representa al BACKEND
│     ├── Expose an API: scope  api://<API_ID>/access_as_user
│     ├── App roles: Admin, Operador, Cliente
│     └── Manifest: requestedAccessTokenVersion = 2
├── App registration "pedidos360-spa"   ← representa al FRONTEND React
│     ├── Plataforma SPA: Redirect URI http://localhost:5173
│     └── API permissions: pedidos360-api / access_as_user
└── Usuarios de prueba con su rol asignado
      admin@...    -> Admin
      operador@... -> Operador
      cliente@...  -> Cliente
```

**¿Por qué dos registros?** El token que usa el frontend es **para la API**: su `aud`
(audience) es la API, y los `roles` vienen de la API. Así API Gateway y Spring pueden
verificar que el token fue emitido *para nosotros* y no para otra aplicación.

---

## Paso 0 — Elegir el tenant (directorio)

Arriba a la derecha en el portal → tu cuenta → **Switch directory**.

- **Opción A: tenant de Duoc.** Normalmente puedes registrar aplicaciones, pero **no crear
  usuarios**. Solo podrías asignarte roles a ti mismo (y a compañeros).
- **Opción B (recomendada): un tenant propio.** Eres administrador y puedes crear los 3 usuarios de prueba.
  1. Buscar **Microsoft Entra ID** → **Manage tenants** → **+ Create**.
  2. Tipo: **Microsoft Entra ID** (workforce). *No* elijas External ID.
  3. Nombre: `Pedidos360`, dominio: `pedidos360<tus-iniciales>` → queda `pedidos360xx.onmicrosoft.com`.
  4. Crear (tarda unos minutos) y luego **Switch directory** al nuevo tenant.

Si no te permite crear un tenant, usa la opción A y asígnate los roles a ti mismo
(puedes probar uno a la vez: quitas un rol y asignas otro).

## Paso 1 — Registrar la API: `pedidos360-api`

1. **Microsoft Entra ID → App registrations → + New registration**.
2. Name: `pedidos360-api` · Supported account types: **Single tenant**
   (*Accounts in this organizational directory only*) · Redirect URI: vacío → **Register**.
3. En **Overview** anota:
   - **Application (client) ID** → este es tu `API_ID`
   - **Directory (tenant) ID** → este es tu `TENANT_ID`

### 1.1 Exponer un scope

4. **Expose an API** → junto a *Application ID URI*, **Add** → aceptar `api://<API_ID>` → Save.
5. **+ Add a scope**:
   - Scope name: `access_as_user`
   - Who can consent: **Admins and users**
   - Admin consent display name: `Acceder a Pedidos360`
   - Admin consent description: `Permite a la app acceder a la API de Pedidos360 en nombre del usuario`
   - State: Enabled → **Add scope**

### 1.2 Crear los roles

6. **App roles → + Create app role**, tres veces:

| Display name | Allowed member types | Value | Description |
|---|---|---|---|
| Administrador | Users/Groups | `Admin` | Administra catálogo y colas |
| Operador | Users/Groups | `Operador` | Gestiona pedidos |
| Cliente | Users/Groups | `Cliente` | Crea y sigue sus pedidos |

> El **Value** debe ser exactamente `Admin`, `Operador`, `Cliente` (mayúscula inicial).
> Es lo que llega en el token y lo que exigen Spring (`hasRole("Admin")`) y el frontend.

### 1.3 Tokens versión 2 (¡importante!)

7. **Manifest** → busca `"requestedAccessTokenVersion"` (dentro de `"api"`) y cámbialo de
   `null` a `2` → **Save**.

> **¿Por qué?** Con `null` (versión 1), Entra emite tokens con
> `iss = https://sts.windows.net/<tenant>/`. Nuestra configuración (y la del caso) espera
> `iss = https://login.microsoftonline.com/<tenant>/v2.0`. Con versión 2 el `aud` es el
> **client ID** de la API (un GUID), no `api://...`. Por eso Spring y API Gateway aceptan
> ambos valores de audience.

## Paso 2 — Registrar el frontend: `pedidos360-spa`

1. **App registrations → + New registration**.
2. Name: `pedidos360-spa` · **Single tenant** · Redirect URI:
   plataforma **Single-page application (SPA)** → `http://localhost:5173` → **Register**.
3. Anota su **Application (client) ID** → este es tu `SPA_ID`.
4. **Authentication**: verifica que `http://localhost:5173` está bajo *Single-page application*.
   No marques *Access tokens* ni *ID tokens* (implicit grant): MSAL usa Authorization Code + PKCE.
   Más adelante agregarás aquí la URL de AWS.
5. **API permissions → + Add a permission → My APIs → pedidos360-api → Delegated →
   `access_as_user` → Add permissions**.
6. **Grant admin consent for <tenant>** → Yes. (Si estás en el tenant de Duoc y no puedes,
   cada usuario aceptará el permiso la primera vez que inicie sesión).

> En la SPA **no se crea Client Secret**: cualquier secreto en React queda expuesto en el navegador.

## Paso 3 — Usuarios de prueba (solo con tenant propio)

**Microsoft Entra ID → Users → + New user → Create new user**, tres veces:

| User principal name | Display name |
|---|---|
| `admin@pedidos360xx.onmicrosoft.com` | Admin Pedidos360 |
| `operador@pedidos360xx.onmicrosoft.com` | Olga Operadora |
| `cliente@pedidos360xx.onmicrosoft.com` | Ana Cliente |

Marca *Auto-generate password* y **anota cada clave temporal**: el primer login pedirá cambiarla.

## Paso 4 — Asignar los roles

1. **Microsoft Entra ID → Enterprise applications** → busca **pedidos360-api**
   (se creó sola al registrar la app).
2. **Users and groups → + Add user/group**:
   - Users: `admin@...` → Select a role: **Administrador** → Assign
   - Repetir: `operador@...` → **Operador**, `cliente@...` → **Cliente**
3. (Recomendado) **Properties → Assignment required? = Yes** → Save.
   Así solo los usuarios con rol pueden obtener token para la API.

> En el plan gratuito de Entra se asignan **usuarios** (no grupos). Es suficiente.

## Paso 5 — Configurar el proyecto

**Frontend** — `frontend-pedidos360/.env.local`:

```
VITE_AUTH_MODE=entra
VITE_ENTRA_CLIENT_ID=<SPA_ID>
VITE_ENTRA_TENANT_ID=<TENANT_ID>
VITE_API_SCOPE=api://<API_ID>/access_as_user
VITE_API_BASE_URL=
```

**Microservicios** — `infra/security/.env`:

```
SECURITY_ENABLED=true
ENTRA_TENANT_ID=<TENANT_ID>
ENTRA_API_CLIENT_ID=<API_ID>
```

Reinicia los microservicios (orders, catalog, mq-admin) y el frontend (`npm run dev`).
En el log de cada servicio debe aparecer `Seguridad activa: issuer=... audiences=...`.

## Paso 6 — Probar

1. http://localhost:5173 → **Iniciar sesión con Microsoft** → entra con `cliente@...`
   (cambia la clave temporal la primera vez).
2. Arriba a la derecha debe aparecer el chip **Cliente**. El menú no muestra Catálogo ni Colas.
3. Crea un pedido: el email queda fijado al tuyo (lo toma la API del token).
4. Cierra sesión y entra con `operador@...`: ves todos los pedidos y puedes aceptarlos.
5. Con `admin@...`: ves Catálogo (editable) y Colas RabbitMQ.

### Ver qué trae el token

En la app, botón **Copiar token** (arriba a la derecha, solo en modo `entra`) y pégalo en
**https://jwt.ms**. Debes ver:

| Claim | Valor esperado |
|---|---|
| `aud` | `<API_ID>` |
| `iss` | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` |
| `scp` | `access_as_user` |
| `roles` | `["Cliente"]` (o el rol del usuario) |
| `preferred_username` | `cliente@...` |

Ese mismo token sirve para probar en Swagger (botón **Authorize**) o Postman
(`Authorization: Bearer <token>`).

## Problemas frecuentes

| Error | Causa | Solución |
|---|---|---|
| `AADSTS50011` redirect URI mismatch | La URL no está registrada exactamente igual | Authentication de `pedidos360-spa`: `http://localhost:5173` sin `/` final y como **SPA** (no Web) |
| `AADSTS65001` consent | Falta el consentimiento del scope | Paso 2.6: Grant admin consent |
| `AADSTS50105` user not assigned | Assignment required = Yes y el usuario no tiene rol | Paso 4 |
| API responde **401** | Token v1 (iss `sts.windows.net`) o `aud` distinto | Paso 1.3 (`requestedAccessTokenVersion: 2`), revisar `ENTRA_API_CLIENT_ID` |
| API responde **403** | El token no trae el rol requerido | Revisar `roles` en jwt.ms; tras asignar un rol, cerrar sesión y volver a entrar |
| Chip "sin rol asignado" | No hay claim `roles` | Paso 4 y volver a iniciar sesión |
| `AADSTS700054` / response_type | Se registró como Web en vez de SPA | Borrar la plataforma Web y agregar SPA |

## Para la presentación: autenticación vs autorización

- **Autenticación (¿quién eres?)**: Entra ID verifica usuario y clave y emite un JWT firmado.
- **Autorización (¿qué puedes hacer?)**: API Gateway verifica que el token es válido para
  nuestra API; Spring Security revisa el **rol** en cada endpoint y las reglas de negocio
  (un Cliente solo ve sus pedidos).
- **IDaaS**: no guardamos contraseñas ni construimos el login; lo delegamos a Microsoft
  (MFA, bloqueo de cuentas, recuperación de clave, etc. vienen incluidos).
