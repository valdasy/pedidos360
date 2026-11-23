# Etapa 8 — Despliegue en AWS Academy (IL 1.3)

Guía para el **Learner Lab**, escrita a partir del despliegue real (probado de punta a punta:
login con Entra → API Gateway → microservicios → Oracle y RabbitMQ → correo en Mailpit).

Orden: Security Groups → 3 EC2 (+ Elastic IP) → RabbitMQ → Oracle → microservicios →
API Gateway → frontend. Unas 2 horas la primera vez.

> **Evaluación 3 (Kafka):** se agrega una 4.ª EC2 `ec2-kafka` y 3 microservicios en `ec2-apps`.
> Pasos en [etapa9-kafka.md → Despliegue en AWS](etapa9-kafka.md#despliegue-en-aws-ec2-kafka).

## Arquitectura en AWS

```
                        Microsoft Entra ID (login, JWT con roles)
                                 ▲
Navegador ── frontend React (localhost:5173, o S3 + CloudFront)
    │
    └─ HTTPS + Bearer ─► API Gateway (HTTP API: JWT authorizer, throttling, CORS)
                              │ HTTP a la Elastic IP
                              ▼
                 ┌──────── EC2 "apps" (Elastic IP) ─────────┐
                 │ orders:8081  catalog:8082  mq-admin:8085 │
                 │ notify (sin puerto)                       │
                 └──────┬──────────────────────┬────────────┘
         AMQP 5672/5673 │ SMTP 1025      JDBC 1521│      (IP privadas, misma VPC)
                        ▼                         ▼
   EC2 "mq": clúster RabbitMQ 2 nodos + Mailpit   EC2 "db": Oracle Free (Docker)
```

> **¿Por qué Oracle en una EC2 y no en RDS?** La política del Learner Lab **bloquea RDS Oracle**
> (`not authorized to perform: rds:CreateDBInstance ... explicit deny ... policy Pvoclabs2`).
> Se usa el mismo `infra/db` de desarrollo en una EC2 (IaaS en vez de PaaS). Los microservicios
> no cambian: solo `DB_HOST` y `DB_SERVICE`. En una cuenta real se usaría RDS (ver Anexo A).

## Reglas del Learner Lab que importan

- Las sesiones duran ~4 h. Al terminar (o con **End Lab**) las **EC2 se detienen** (no se borran)
  y su IP pública **cambia** al volver → la EC2 apps usa **Elastic IP**.
- Presupuesto en **créditos** (no se cobra a tu tarjeta). Pulsa **End Lab** al terminar.
- No se pueden crear roles IAM: desactivar todo lo que pida uno (monitoreo mejorado, etc.).
- Región: **us-east-1**. Key pair: **vockey**.

## Paso 1 — Security Groups (EC2 → Grupos de seguridad → Crear)

⚠️ **Errores que nos pasaron:**

- La pantalla tiene **dos secciones**: *Reglas de entrada* (arriba) y *Reglas de salida* (abajo).
  Las reglas van en **entrada**. La salida se deja como viene: **Todo el tráfico → 0.0.0.0/0**.
  Si se reemplaza la salida, la EC2 no puede descargar Docker ni clonar el repo.
- Crear **primero** `apps`: los otros dos lo usan como origen.
- *Mi IP* guarda la IP de **donde estás ahora**. Si cambias de lugar (casa ↔ Duoc), edita esas
  reglas y vuelve a elegir *Mi IP*.

| Grupo | Tipo / puerto | Origen | Para qué |
|---|---|---|---|
| `pedidos360-apps` | SSH 22 | Mi IP | |
| | TCP 8081, 8082, 8085 | Anywhere-IPv4 | API Gateway llama desde internet |
| | SSH 22 | `18.206.107.24/29` | EC2 Instance Connect (consola web) |
| `pedidos360-mq` | SSH 22 | Mi IP + `18.206.107.24/29` | |
| | TCP 5672, 5673, 15672, 15673, 1025 | `pedidos360-apps` | AMQP, Management API, SMTP |
| | TCP 15672, 15673, 8025 | Mi IP | UI de RabbitMQ y Mailpit para la demo |
| `pedidos360-db` | SSH 22 | Mi IP + `18.206.107.24/29` | |
| | TCP 1521 | `pedidos360-apps` | Oracle |

Resultado esperado (entrada/salida): apps **5/1**, mq **10/1**, db **3/1**.

`18.206.107.24/29` es el rango de **EC2 Instance Connect** en us-east-1: la terminal web no se
conecta desde tu IP sino desde servidores de AWS, por eso *Mi IP* no basta.

RabbitMQ y Oracle **no** quedan expuestos a internet: solo la EC2 apps puede hablarles.

## Paso 2 — Lanzar las 3 EC2

EC2 → **Lanzar instancias** (una vez por fila). En todas: **Amazon Linux 2023** (x86),
key pair **vockey**, **20 GiB** gp3, *Configuración de red → Editar → Seleccionar grupo existente*
(quitar `launch-wizard-*` o `default`), y en **Detalles avanzados → Datos de usuario** pegar
`infra/aws/ec2-user-data.sh` (Docker, Compose, Buildx, Git y 2 GB de swap).

| Nombre | Tipo | Grupo de seguridad |
|---|---|---|
| `ec2-mq` | t3.small | `pedidos360-mq` |
| `ec2-db` | **t3.medium** (Oracle necesita ~2 GB) | `pedidos360-db` |
| `ec2-apps` | t3.medium o **t3/t2.large** (4 JVM + compilación) | `pedidos360-apps` |

**Elastic IP** (solo `ec2-apps`): *Red y seguridad → Direcciones IP elásticas → Asignar* →
*Acciones → Asociar* → instancia `ec2-apps`. Es la IP que usa API Gateway y no cambia entre
sesiones. (La lista de Instancias puede tardar en mostrarla: pulsar ⟳.)

**Anotar las IP privadas** (`172.31.x.x`) de `ec2-mq` y `ec2-db`: las usa `ec2-apps`.

**Conectarse:** marcar la instancia → **Conectar** → *Conexión de la instancia EC2* → Conectar.
El prompt debe mostrar `ec2-user@ip-172-31-...`. (El ícono `>_` de la barra superior es
**CloudShell**, otra cosa: se usa en el Paso 6.)

Comprobar que el user data terminó (~3 min tras lanzar):

```bash
cat ~/docker-listo.txt
```

El repo es público, así que se clona sin credenciales. Si fuera privado: token *fine-grained*
de solo lectura (*Contents: Read-only*) como contraseña.

## Paso 3 — RabbitMQ en `ec2-mq`

```bash
git clone https://github.com/valdasy/pedidos360.git && cd ~/pedidos360/infra/mq-cluster
cp .env.example .env && nano .env      # RABBITMQ_PASS y ERLANG_COOKIE (solo letras y números)
grep RABBITMQ_ .env                    # confirmar que nano guardó (Ctrl+O, Enter, Ctrl+X)
docker compose up -d && docker compose run --rm topology-init
```

Verificar:

```bash
docker exec pedidos360-rabbit1 rabbitmqctl cluster_status | grep -A3 "Running Nodes"
docker exec pedidos360-rabbit1 rabbitmqctl list_queues name leader messages
```

Deben aparecer `rabbit@rabbit1` y `rabbit@rabbit2`, y 6 colas repartidas entre ambos.
UI: `http://<IP pública ec2-mq>:15672` y `:15673`. Mailpit: `:8025`.

> El reparto de colas entre nodos (`balanced`) depende del orden de creación y **puede ser distinto
> al de tu PC**. Antes de demostrar "cae un nodo", revisar `list_queues` para saber qué cola vive dónde.

Si olvidaste la clave: `grep RABBITMQ_PASS .env`. Para cambiarla:
`docker exec pedidos360-rabbit1 rabbitmqctl change_password pedidos360 <nueva>` y actualizar el `.env`.

## Paso 4 — Oracle en `ec2-db`

```bash
git clone https://github.com/valdasy/pedidos360.git && cd ~/pedidos360/infra/db
cp .env.example .env && nano .env      # 3 claves, DB_PORT=1521
grep PASSWORD .env
docker compose up -d && docker compose logs -f
```

Esperar `Usuarios pedidos_orders y pedidos_catalog creados en FREEPDB1` y
`DATABASE IS READY TO USE!` (~2-4 min la primera vez). `Ctrl+C` para salir de los logs.
El aviso `Unable to obtain current patch information` es normal en la versión Free.

## Paso 5 — Microservicios en `ec2-apps`

```bash
git clone https://github.com/valdasy/pedidos360.git && cd ~/pedidos360/infra/apps
```

Crear el `.env` reemplazando las IP privadas y las claves (deben coincidir con las de mq y db):

```bash
cat > .env <<'EOF'
RABBITMQ_HOST=<IP privada ec2-mq>
RABBITMQ_USER=pedidos360
RABBITMQ_PASS=<RABBITMQ_PASS de ec2-mq>
RABBITMQ_ADDRESSES=<IP privada ec2-mq>:5672,<IP privada ec2-mq>:5673
RABBITMQ_MANAGEMENT_URLS=http://<IP privada ec2-mq>:15672/api,http://<IP privada ec2-mq>:15673/api
SMTP_HOST=<IP privada ec2-mq>
SMTP_PORT=1025
DB_HOST=<IP privada ec2-db>
DB_PORT=1521
DB_SERVICE=FREEPDB1
ORDERS_DB_PASSWORD=<de ec2-db>
CATALOG_DB_PASSWORD=<de ec2-db>
SECURITY_ENABLED=true
ENTRA_TENANT_ID=<Id. de directorio (inquilino)>
ENTRA_API_CLIENT_ID=<Id. de aplicación de "Pedidos360 API">
EOF
```

Usar **IP privadas**, nunca `localhost` (para `ec2-apps`, localhost es ella misma) ni IP públicas
(cambian y el Security Group no las permite).

```bash
docker compose up -d --build     # ~1-10 min la primera vez según el tipo de instancia
docker compose ps                # 4 servicios "Up"
```

Desde tu PC: `http://<Elastic IP>:8081/actuator/health`, `:8082` y `:8085` → `{"status":"UP"}`.
`http://<Elastic IP>:8082/api/catalog/products` → **401** (correcto: exige token de Entra).

## Paso 6 — API Gateway (AWS CloudShell)

CloudShell = ícono `>_` en la barra superior de la consola (ya tiene permisos del lab).

```bash
git clone -q https://github.com/valdasy/pedidos360.git && cd ~/pedidos360/infra/aws
sed -i 's#^EC2_APPS_HOST=.*#EC2_APPS_HOST="<Elastic IP>"#' api-gateway.sh
sed -i 's#^ENTRA_TENANT_ID=.*#ENTRA_TENANT_ID="<tenant>"#' api-gateway.sh
sed -i 's#^ENTRA_API_CLIENT_ID=.*#ENTRA_API_CLIENT_ID="<client id de la API>"#' api-gateway.sh
grep -E '^(EC2_APPS_HOST|ENTRA_|FRONTEND_ORIGINS)' api-gateway.sh
bash api-gateway.sh
```

Crea el HTTP API, el autorizador JWT, 21 rutas y el stage con throttling, y muestra la URL
`https://<id>.execute-api.us-east-1.amazonaws.com`. Detalles: **docs/etapa7-api-gateway.md**.

Comprobar: `curl -i <URL>/api/catalog/products` → **401**.

## Paso 7 — Frontend

MSAL exige **HTTPS** para cualquier Redirect URI que no sea `localhost`.

### Opción A — local apuntando a AWS (la que usamos)

```bash
cd frontend-pedidos360 && cp .env.example .env.local
```

```
VITE_AUTH_MODE=entra
VITE_ENTRA_CLIENT_ID=<Id. de aplicación de "Pedidos360 Frontend">
VITE_ENTRA_TENANT_ID=<tenant>
VITE_API_SCOPE=api://<client id de la API>/user_impersonation
VITE_API_BASE_URL=<URL de API Gateway>
```

El nombre del scope es el que aparece en *Pedidos360 API → Exponer una API* (en nuestro tenant,
`user_impersonation`). Luego `npm install && npm run dev` y abrir `http://localhost:5173`.
`.env.local` no se sube a GitHub: hay que crearlo en cada PC (el del Duoc incluido).

### Opción B — S3 + CloudFront (frontend también en la nube)

1. `npm run build` con la URL del gateway en `.env.local`.
2. S3 → bucket `pedidos360-front-<iniciales>` (acceso público bloqueado) → subir el **contenido** de `dist/`.
3. CloudFront → distribución con origen el bucket y **Origin access control**; root object `index.html`;
   aplicar la bucket policy que propone.
4. **Error pages**: 403 y 404 → `/index.html` con código 200 (rutas de React).
5. Agregar `https://<id>.cloudfront.net` como Redirect URI **SPA** en *Pedidos360 Frontend* y en
   `FRONTEND_ORIGINS` (CORS) del gateway.

## Al terminar y al volver

- **Terminar:** *End Lab*. Las EC2 se detienen; los datos (volúmenes Docker, `.env`) se conservan.
- **Volver:** *Start Lab* → las EC2 arrancan solas o con *Iniciar instancia*. Los contenedores
  tienen `restart: unless-stopped` y se levantan solos. Esperar ~3 min (Oracle es el más lento).
- Cambian las IP **públicas** de ec2-mq y ec2-db (solo afecta a abrir las UI de RabbitMQ/Mailpit).
  No cambian las privadas ni la Elastic IP → la API y el `.env` de apps siguen sirviendo.

## Antes de presentar (checklist)

- [ ] Learner Lab iniciado 20 min antes; 3 EC2 *En ejecución*
- [ ] Reglas *Mi IP* actualizadas si estás en otra red (Duoc)
- [ ] ec2-mq: `docker compose ps` (rabbit1, rabbit2, mailpit `Up`)
- [ ] ec2-db: `docker compose ps` (oracle `healthy`)
- [ ] ec2-apps: `docker compose ps` (4 servicios `Up`)
- [ ] `curl -i <API>/api/orders` → 401
- [ ] `.env.local` del frontend en el PC de la presentación; login con los 3 usuarios
- [ ] UI de RabbitMQ y Mailpit abiertas en pestañas (IP pública **nueva** de ec2-mq)

## Problemas frecuentes (los que nos pasaron)

| Síntoma | Causa | Solución |
|---|---|---|
| `rds:CreateDBInstance ... explicit deny` | El lab bloquea RDS Oracle | Oracle en `ec2-db` (Paso 4) |
| SG con "0 reglas de entrada" y la EC2 sin internet | Reglas puestas en **salida** | Salida: solo *Todo el tráfico 0.0.0.0/0*; reglas en entrada. Recrear las EC2 (el user data corre una vez) |
| `cat: /home/cloudshell-user/...` | Estás en CloudShell, no en la EC2 | *Instancias → Conectar → Conexión de la instancia EC2* |
| Instance Connect no conecta | Falta SSH desde `18.206.107.24/29` | Agregar la regla |
| `./mvnw: Permission denied` al compilar | `mvnw` sin permiso de ejecución (repo creado en Windows) | Corregido en el repo; en un clon antiguo: `chmod +x ~/pedidos360/ms-*/mvnw` |
| No recuerdo la clave de RabbitMQ/Oracle | — | `grep PASS .env` en la EC2 correspondiente |
| catalog/orders no arrancan: `ORA-12170` / timeout | Falta 1521 desde `pedidos360-apps` o Oracle detenido | Revisar SG y `docker compose ps` en ec2-db |
| `ORA-01017 invalid username/password` | Claves distintas entre `infra/db/.env` y `infra/apps/.env` | Igualarlas |
| notify: `Connection refused` a RabbitMQ | IP pública o `localhost` en `.env`, o faltan puertos en el SG | IP privada de ec2-mq |
| Página en blanco tras el login | Error de MSAL al volver de Microsoft | Corregido: ahora se muestra el error |
| "Failed to fetch" + miles de peticiones `orders` | Bucle de peticiones en modo Entra | Corregido en el repo (`AuthContext.tsx`) |
| API Gateway 503 tras reiniciar el lab | EC2 apps sin Elastic IP | Asociar Elastic IP y recrear el gateway |

## Anexo A — Amazon RDS for Oracle (cuenta AWS real)

En una cuenta sin las restricciones del lab: RDS → *Creación estándar* → Oracle SE2 *License
included* → Dev/Test, Single-AZ → `pedidos360-db`, usuario `admin`, credenciales autoadministradas
→ db.t3.small, 20 GiB gp3 sin autoescalado → sin acceso público, SG `pedidos360-db` →
*Configuración adicional → Nombre de base de datos inicial `ORCL`*. Crear los usuarios desde
`ec2-apps`:

```bash
docker run --rm -it -v ~/pedidos360/infra/aws:/sql --entrypoint sqlplus gvenzl/oracle-free:23-slim-faststart admin@//<ENDPOINT-RDS>:1521/ORCL @/sql/rds-usuarios.sql
```

En `infra/apps/.env`: `DB_HOST=<endpoint RDS>` y `DB_SERVICE=ORCL`. Nada más cambia.
