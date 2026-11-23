# Pedidos360

Proyecto semestral de **Desarrollo Cloud Native I (DSY1107)** — Duoc UC.

Sistema de gestión de pedidos construido con microservicios, autenticación con
**Microsoft Entra ID** (IDaaS), **AWS API Gateway** como API Manager y mensajería
asíncrona con **RabbitMQ** (comandos) y **Apache Kafka** (eventos de negocio), desplegado en
**AWS EC2** con Docker Compose.

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

  orders  ──► Kafka: pedidos.eventos ───┬──► grupo pedidos360-audit  ──► ms-pedidos360-audit  (timeline)
  catalog ──► Kafka: inventario.eventos ┘
              Kafka: pedidos.eventos ──────► grupo pedidos360-report ──► ms-pedidos360-report (KPIs)
              *.DLT (errores)                ms-pedidos360-kafka-admin (tópicos y consumer groups)
```

## Estructura del repositorio (monorepo)

Cada carpeta `ms-*` es un **microservicio independiente**: tiene su propio
`pom.xml`, su propio `Dockerfile` y se compila y despliega por separado.

| Carpeta | Descripción |
|---|---|
| `frontend-pedidos360/` | SPA React + TypeScript + Vite + MSAL |
| `ms-pedidos360-orders/` | Pedidos y estados. Productor RabbitMQ y Kafka (`pedidos.eventos`) |
| `ms-pedidos360-catalog/` | Productos, stock y precios. Productor Kafka (`inventario.eventos`) |
| `ms-pedidos360-notify/` | Consumidor RabbitMQ (email, kitchen, invoice) |
| `ms-pedidos360-mq-admin/` | Administración de colas y DLQ |
| `ms-pedidos360-audit/` | Consumidor Kafka: auditoría / timeline (Evaluación 3) |
| `ms-pedidos360-report/` | Consumidor Kafka: KPIs y reportes (Evaluación 3) |
| `ms-pedidos360-kafka-admin/` | Administración de tópicos y consumer groups (Evaluación 3) |
| `infra/mq-cluster/` | **Broker principal**: clúster RabbitMQ de 2 nodos + Mailpit (Parte 3) |
| `infra/mq/` | Topología (`definitions.json`) y alternativa de 1 nodo |
| `infra/kafka/` | Clúster Kafka: ZooKeeper + 2 brokers + Kafka UI, y creación de tópicos |
| `infra/db/` | Oracle Free en Docker (local y EC2 db) |
| `infra/apps/` | Docker Compose de los microservicios (EC2 apps) |
| `infra/security/` | Configuración de Entra ID para los microservicios |
| `infra/aws/` | Scripts de API Gateway, EC2 (user data) y RDS |
| `scripts/` | Pruebas de punta a punta |
| `docs/` | Guías de Entra ID, API Gateway, despliegue AWS, Kafka y **guía de estudio** |

## Stack

| Capa | Tecnología |
|---|---|
| Frontend | React, TypeScript, Vite, `@azure/msal-react` |
| Backend | Java 25, Spring Boot 4, Maven |
| Identidad (IDaaS) | Microsoft Entra ID |
| API Manager | AWS API Gateway (HTTP API + JWT Authorizer) |
| Mensajería | RabbitMQ (comandos) · Apache Kafka con ZooKeeper (eventos) |
| Base de datos | Oracle Free en Docker (local y EC2; RDS Oracle bloqueado en el Learner Lab) |
| Infraestructura | AWS EC2, Docker, Docker Compose |

## Avance

- [x] Etapa 0 — Repositorio y entorno
- [x] Etapa 1 — RabbitMQ: topología (IL 2.1)
- [x] Etapa 2 — Productor `orders` y consumidor `notify` (IL 2.2)
- [x] Etapa 3 — Administración de colas, DLQ, `mq-admin` (IL 2.3)
- [x] Etapa 4 — Dominio: catálogo, pedidos y Oracle
- [x] Etapa 5 — Frontend React con MSAL + Entra ID configurado (login real con roles) → [docs/etapa5-entra-id.md](docs/etapa5-entra-id.md)
- [x] Etapa 6 — Spring Security: JWT y roles (probado con tokens reales de Entra)
- [x] Etapa 7 — AWS API Gateway con JWT Authorizer, CORS y throttling → [docs/etapa7-api-gateway.md](docs/etapa7-api-gateway.md)
- [x] Etapa 8 — Desplegado en AWS: 3 EC2 (apps, mq, db) + Elastic IP → [docs/etapa8-despliegue-aws.md](docs/etapa8-despliegue-aws.md)
- [x] Parte 3 — Clúster RabbitMQ de 2 nodos (local y en AWS)
- [x] Etapa 9 — **Kafka (Evaluación 3)**: 2 brokers, 2 tópicos, audit, report, kafka-admin, DLT
  (probado en local, 40/40) · [ ] desplegar `ec2-kafka` → [docs/etapa9-kafka.md](docs/etapa9-kafka.md)

**En AWS (probado de punta a punta):** login con Entra → API Gateway → microservicios en
`ec2-apps` → Oracle en `ec2-db` y RabbitMQ en `ec2-mq` → correo en Mailpit.
Oracle corre en una EC2 porque el Learner Lab bloquea RDS Oracle.

📚 **Para estudiar:** [docs/guia-estudio.md](docs/guia-estudio.md)

## Requisitos para levantarlo

- Java 25, Maven 3.9+
- Node.js 20+
- Docker Desktop
- Git

## Levantar en local (Git Bash)

```bash
# 1. Infraestructura (la primera vez: cp .env.example .env en cada carpeta)
cd infra/mq-cluster && docker compose up -d && docker compose run --rm topology-init && cd ../..
cd infra/db && docker compose up -d && cd ../..      # esperar "DATABASE IS READY TO USE!"
cd infra/kafka && docker compose up -d && docker compose --profile init run --rm topics-init && cd ../..

# 2. Microservicios, cada uno en su propia terminal
cd ms-pedidos360-catalog  && ./mvnw spring-boot:run   # :8082
cd ms-pedidos360-orders   && ./mvnw spring-boot:run   # :8081
cd ms-pedidos360-notify   && ./mvnw spring-boot:run   # consumidor
cd ms-pedidos360-mq-admin && ./mvnw spring-boot:run   # :8085
cd ms-pedidos360-audit       && ./mvnw spring-boot:run   # :8086 (Kafka)
cd ms-pedidos360-report      && ./mvnw spring-boot:run   # :8087 (Kafka)
cd ms-pedidos360-kafka-admin && ./mvnw spring-boot:run   # :8088 (Kafka)
cd frontend-pedidos360   && npm install && npm run dev   # :5173 (modo práctica)

# 3. Pruebas
bash scripts/probar-pedidos.sh       # ciclo de vida del pedido
bash scripts/probar-mensajeria.sh    # RabbitMQ: ACK, DLQ, idempotencia
bash scripts/probar-admin.sh         # mq-admin: reproceso, alertas
bash scripts/probar-cluster.sh       # cae un nodo y luego el otro (~4 min)
bash scripts/probar-kafka.sh         # Kafka: productores, consumidores, DLT, grupos, admin
```

| Servicio | URL |
|---|---|
| Swagger orders | http://localhost:8081/swagger-ui.html |
| Swagger catalog | http://localhost:8082/swagger-ui.html |
| Swagger mq-admin | http://localhost:8085/swagger-ui.html |
| RabbitMQ nodo 1 / nodo 2 | http://localhost:15672 · http://localhost:15673 |
| Mailpit | http://localhost:8025 |
| Swagger audit / report / kafka-admin | http://localhost:8086/swagger-ui.html · :8087 · :8088 |
| Kafka UI | http://localhost:8090 |
