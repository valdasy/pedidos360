# Pedidos360

Proyecto semestral de **Desarrollo Cloud Native I (DSY1107)** — Duoc UC.

Sistema de gestión de pedidos construido con microservicios, autenticación con
**Microsoft Entra ID** (IDaaS), **AWS API Gateway** como API Manager y mensajería
asíncrona con **RabbitMQ**, desplegado en **AWS EC2** con Docker Compose.

## Arquitectura

```
Usuario ──login──► Microsoft Entra ID ──JWT──┐
                                             ▼
Frontend React + MSAL ──Bearer JWT──► AWS API Gateway (JWT Authorizer)
                                             │
                    ┌────────────────────────┴───────────────┐
                    ▼                                        ▼
           ms-pedidos360-orders                    ms-pedidos360-catalog
           (Spring Security + roles)               (Spring Security + roles)
                    │ publica comandos
                    ▼
     RabbitMQ: cmd.direct / cmd.topic ──► q.cmd.email | q.cmd.kitchen | q.cmd.invoice
               cmd.dead.dlx           ──► *.dlq
                    │ consume (asíncrono)
                    ▼
           ms-pedidos360-notify            ms-pedidos360-mq-admin
           (email, kitchen, invoice)       (monitoreo y reproceso de DLQ)
```

## Estructura del repositorio (monorepo)

Cada carpeta `ms-*` es un **microservicio independiente**: tiene su propio
`pom.xml`, su propio `Dockerfile` y se compila y despliega por separado.

| Carpeta | Descripción |
|---|---|
| `frontend-pedidos360/` | SPA React + TypeScript + Vite + MSAL |
| `ms-pedidos360-orders/` | Pedidos y estados. Productor RabbitMQ |
| `ms-pedidos360-catalog/` | Productos, stock y precios |
| `ms-pedidos360-notify/` | Consumidor RabbitMQ (email, kitchen, invoice) |
| `ms-pedidos360-mq-admin/` | Administración de colas y DLQ |
| `infra/mq/` | Docker Compose de RabbitMQ + topología |
| `infra/apps/` | Docker Compose de los microservicios |
| `docs/` | Documentación técnica y evidencias |

## Stack

| Capa | Tecnología |
|---|---|
| Frontend | React, TypeScript, Vite, `@azure/msal-react` |
| Backend | Java 25, Spring Boot 4, Maven |
| Identidad (IDaaS) | Microsoft Entra ID |
| API Manager | AWS API Gateway (HTTP API + JWT Authorizer) |
| Mensajería | RabbitMQ |
| Base de datos | Oracle (local: Docker / nube: Amazon RDS) |
| Infraestructura | AWS EC2, Docker, Docker Compose |

## Avance

- [x] Etapa 0 — Repositorio y entorno
- [x] Etapa 1 — RabbitMQ: topología (IL 2.1)
- [x] Etapa 2 — Productor `orders` y consumidor `notify` (IL 2.2)
- [x] Etapa 3 — Administración de colas, DLQ, `mq-admin` (IL 2.3)
- [x] Etapa 4 — Dominio: catálogo, pedidos y Oracle
- [ ] Etapa 5 — Entra ID + frontend React con MSAL (IL 1.2)
- [ ] Etapa 6 — Spring Security: JWT y roles
- [ ] Etapa 7 — AWS API Gateway con JWT Authorizer (IL 1.1)
- [ ] Etapa 8 — Despliegue en AWS EC2 (IL 1.3)

## Requisitos para levantarlo

- Java 25, Maven 3.9+
- Node.js 20+
- Docker Desktop
- Git

## Levantar en local (Git Bash)

```bash
# 1. Infraestructura (la primera vez: cp .env.example .env en cada carpeta)
cd infra/mq && docker compose up -d && docker compose run --rm topology-init && cd ../..
cd infra/db && docker compose up -d && cd ../..      # esperar "DATABASE IS READY TO USE!"

# 2. Microservicios, cada uno en su propia terminal
cd ms-pedidos360-catalog  && ./mvnw spring-boot:run   # :8082
cd ms-pedidos360-orders   && ./mvnw spring-boot:run   # :8081
cd ms-pedidos360-notify   && ./mvnw spring-boot:run   # consumidor
cd ms-pedidos360-mq-admin && ./mvnw spring-boot:run   # :8085

# 3. Pruebas
bash scripts/probar-pedidos.sh       # ciclo de vida del pedido
bash scripts/probar-mensajeria.sh    # RabbitMQ: ACK, DLQ, idempotencia
bash scripts/probar-admin.sh         # mq-admin: reproceso, alertas
```

| Servicio | URL |
|---|---|
| Swagger orders | http://localhost:8081/swagger-ui.html |
| Swagger catalog | http://localhost:8082/swagger-ui.html |
| Swagger mq-admin | http://localhost:8085/swagger-ui.html |
| RabbitMQ | http://localhost:15672 |
| Mailpit | http://localhost:8025 |
