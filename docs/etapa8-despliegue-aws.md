# Etapa 8 — Despliegue en AWS Academy (IL 1.3)

Guía para el **Learner Lab**. Orden recomendado (unas 2 horas la primera vez):
Security Groups → EC2 mq → RDS → EC2 apps → API Gateway (Etapa 7) → frontend → Entra.

## Arquitectura en AWS

```
                        Microsoft Entra ID (login, JWT)
                                 ▲
Navegador ─ HTTPS ─► CloudFront + S3 (React)                  (o frontend local: localhost:5173)
    │
    └─ HTTPS + Bearer ─► API Gateway (HTTP API, JWT authorizer, throttling, CORS)
                              │ HTTP
                              ▼
                 ┌──────── EC2 "apps" (Elastic IP) ─────────┐
                 │ orders:8081  catalog:8082  mq-admin:8085 │
                 │ notify (sin puerto)                       │
                 └──────┬──────────────────────┬────────────┘
                AMQP 5672│ SMTP 1025      JDBC 1521│
                        ▼                         ▼
           EC2 "mq": RabbitMQ + Mailpit     Amazon RDS for Oracle
```

## Reglas del Learner Lab que importan

- Las sesiones duran ~4 h. Al terminar, las **EC2 se detienen** (no se borran) y la IP
  pública **cambia** al volver a encenderlas → usar **Elastic IP** en la EC2 apps.
- Tienes un presupuesto limitado: **detén RDS y EC2** cuando no estés trabajando.
- No se pueden crear roles IAM; usa el **LabRole/LabInstanceProfile** si algo lo pide.
- Región: **us-east-1**. Key pair: **vockey** (descarga `labsuser.pem` en *AWS Details*).

## Paso 1 — Security Groups (EC2 → Security Groups → Create)

| Nombre | Entrada | Origen |
|---|---|---|
| `sg-pedidos360-apps` | 22 (SSH) | My IP |
| | 8081, 8082, 8085 | 0.0.0.0/0 (API Gateway llama desde internet) |
| `sg-pedidos360-mq` | 22 | My IP |
| | 5672 (AMQP), 1025 (SMTP) | `sg-pedidos360-apps` |
| | 15672 (UI RabbitMQ), 8025 (UI Mailpit) | My IP |
| `sg-pedidos360-db` | 1521 | `sg-pedidos360-apps` |

Abrir solo los puertos necesarios es una exigencia del caso. RabbitMQ y la base **no** quedan
expuestos a internet: solo la EC2 apps puede hablar con ellos.

## Paso 2 — EC2 "mq" (RabbitMQ + Mailpit)

1. EC2 → **Launch instance**: name `ec2-mq`, **Amazon Linux 2023**, **t3.small**,
   key pair **vockey**, security group `sg-pedidos360-mq`, disco 20 GB.
2. **Advanced details → User data**: pegar `infra/aws/ec2-user-data.sh`. Launch.
3. Esperar ~3 min y conectarse (EC2 → Connect → **EC2 Instance Connect**, o SSH con `labsuser.pem`).
4. Verificar que Docker quedó instalado:

```bash
cat ~/docker-listo.txt
```

5. Clonar el repositorio. Si es privado, crea un token en GitHub
   (*Settings → Developer settings → Fine-grained tokens*, solo lectura de este repo) y úsalo como clave:

```bash
git clone https://github.com/valdasy/pedidos360.git && cd pedidos360/infra/mq
```

6. Configurar y levantar:

```bash
cp .env.example .env && nano .env
```

```bash
docker compose up -d && docker compose run --rm topology-init
```

7. Anotar la **IP privada** de la instancia (EC2 → la instancia → *Private IPv4*). La usará la EC2 apps.

Comprobar: `http://<IP pública ec2-mq>:15672` (UI de RabbitMQ) desde tu PC.

## Paso 3 — Amazon RDS for Oracle

1. RDS → **Create database → Standard create**.
2. Engine **Oracle** → edition **Oracle Standard Edition Two** → **License included**.
3. Template **Dev/Test** (o Free tier si aparece). Deployment: **Single-AZ**.
4. DB instance identifier `pedidos360-db` · master username `admin` · master password (anótala).
5. Instance class: la más pequeña disponible (**db.t3.small**). Storage 20 GB gp3, sin autoscaling.
6. Connectivity: **Don't connect to an EC2**, default VPC, **Public access: No**,
   security group `sg-pedidos360-db`.
7. **Additional configuration → Initial database name: `ORCL`** (si lo dejas vacío no se crea).
   Desactivar backups automáticos y Performance Insights para ahorrar.
8. Create (tarda 15-25 min). Anotar el **Endpoint**.

> Si el Learner Lab no permite Oracle, alternativa: correr `infra/db` (Oracle Free en Docker)
> en la EC2 apps o mq (requiere t3.medium+). Los microservicios no cambian: solo `DB_HOST`.

**Crear los usuarios** (desde la EC2 apps, cuando exista, porque RDS no es público):

```bash
docker run --rm -it -v ~/pedidos360/infra/aws:/sql --entrypoint sqlplus gvenzl/oracle-free:23-slim-faststart admin@//<ENDPOINT-RDS>:1521/ORCL @/sql/rds-usuarios.sql
```

Pedirá la clave maestra y las dos claves nuevas. Las **tablas** las crea Flyway cuando
arranquen los microservicios.

## Paso 4 — EC2 "apps" (microservicios)

1. Launch instance: `ec2-apps`, Amazon Linux 2023, **t3.medium** (4 JVM; t3.large si está
   disponible), vockey, `sg-pedidos360-apps`, 20 GB, el mismo user data.
2. **Elastic IP**: EC2 → Elastic IPs → Allocate → Associate a `ec2-apps`. Esta IP no cambia
   entre sesiones del lab: es la que usa API Gateway.
3. Conectarse, clonar el repo y configurar:

```bash
git clone https://github.com/valdasy/pedidos360.git && cd pedidos360/infra/apps
```

```bash
cp .env.example .env && nano .env
```

   - `RABBITMQ_HOST` y `SMTP_HOST` = IP privada de ec2-mq
   - `DB_HOST` = endpoint de RDS, `DB_SERVICE=ORCL`
   - `SECURITY_ENABLED=true` y los IDs de Entra

4. Compilar y levantar (la primera vez ~5 min):

```bash
docker compose up -d --build
```

```bash
docker compose logs -f orders
```

Esperar `Started` en los 4 servicios.

5. Probar desde tu PC: `http://<EIP>:8082/actuator/health` → `{"status":"UP"}`.

## Paso 5 — API Gateway

Seguir **docs/etapa7-api-gateway.md** con `EC2_APPS_HOST=<EIP>`.

## Paso 6 — Frontend

MSAL exige **HTTPS** para cualquier Redirect URI que no sea `localhost`.

### Opción A — local apuntando a AWS (la más simple y sirve para la presentación)

En el PC (el tuyo o el del Duoc):

```bash
cd frontend-pedidos360 && cp .env.example .env.local
```

`VITE_AUTH_MODE=entra`, IDs de Entra y `VITE_API_BASE_URL=<URL de API Gateway>`. Luego:

```bash
npm install && npm run dev
```

El frontend corre en `localhost:5173` (Redirect URI ya registrado) y **todo el backend está en AWS**.

### Opción B — S3 + CloudFront (frontend también en la nube)

1. Compilar con la URL del gateway en `.env.local`:

```bash
npm run build
```

2. S3 → Create bucket `pedidos360-front-<iniciales>` (bloqueo de acceso público activado).
3. Subir el **contenido** de `dist/`.
4. CloudFront → Create distribution → origin: el bucket, **Origin access control** (crear),
   default root object `index.html`. Aplicar la bucket policy que propone CloudFront.
5. **Error pages**: 403 y 404 → `/index.html` con código 200 (rutas de React como `/orders`).
6. Agregar `https://<id>.cloudfront.net` como Redirect URI **SPA** en `pedidos360-spa` (Entra)
   y en **Allow origins** del CORS de API Gateway.

## Antes de presentar (checklist)

- [ ] Learner Lab iniciado 20 min antes
- [ ] Iniciar RDS (si estaba detenido tarda ~10 min) y las 2 EC2
- [ ] ec2-mq: `docker compose ps` (RabbitMQ y Mailpit `Up`; tienen `restart: unless-stopped`)
- [ ] ec2-apps: `docker compose ps` (4 servicios `Up`)
- [ ] `curl -i <API>/api/orders` → 401 (gateway activo)
- [ ] Login con los 3 usuarios de Entra
- [ ] UI de RabbitMQ y Mailpit abiertas en pestañas

## Problemas frecuentes

| Síntoma | Causa probable |
|---|---|
| catalog/orders no arrancan: `ORA-12170` / timeout | `sg-pedidos360-db` no permite 1521 desde `sg-pedidos360-apps`, o RDS detenido |
| `ORA-01017 invalid username/password` | Clave distinta entre `rds-usuarios.sql` y `.env` |
| notify: `Connection refused` a RabbitMQ | `RABBITMQ_HOST` usa IP pública (usar la privada) o falta 5672 en `sg-pedidos360-mq` |
| La compilación se queda sin memoria | Instancia muy pequeña: el user data agrega 2 GB de swap; usar t3.medium |
| API Gateway 503 tras reiniciar el lab | EC2 sin Elastic IP: cambió la IP pública |
