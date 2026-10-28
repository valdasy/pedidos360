# infra/apps — Microservicios en Docker (EC2 "apps")

Levanta catalog, orders, notify y mq-admin como contenedores. Cada uno se compila con
su `Dockerfile` (multi-stage: Maven + JDK para compilar, solo JRE para ejecutar).

```bash
cp .env.example .env          # completar IPs de RabbitMQ/RDS y claves
docker compose up -d --build  # la primera vez tarda ~2-5 min (descarga dependencias)
docker compose ps
docker compose logs -f orders
```

| Servicio | Puerto | Público |
|---|---|---|
| orders | 8081 | sí, vía API Gateway |
| catalog | 8082 | sí, vía API Gateway |
| mq-admin | 8085 | sí, vía API Gateway (solo Admin) |
| notify | — | no: solo consume de RabbitMQ |

Dentro de la red de Docker, orders llama a catalog como `http://catalog:8082`
(los contenedores se encuentran por nombre de servicio).

## Prueba local

Con `infra/mq` e `infra/db` corriendo en tu PC, usa en `.env` los valores de la sección
"Prueba local" de `.env.example` (`host.docker.internal`, `DB_PORT=1522`).
Detén antes los microservicios que tengas corriendo con `./mvnw spring-boot:run`
(mismos puertos) y deja **una sola** instancia de notify.

Probado: `scripts/probar-pedidos.sh` 29/29, `probar-mensajeria.sh` 12/12 y
`probar-admin.sh` 12/12 contra los contenedores.

## Una sola instancia de notify

La idempotencia de notify es en memoria de cada instancia. Con 2 instancias, un mensaje
duplicado puede llegar a la otra y procesarse dos veces (se comprobó en las pruebas).
Para escalar notify horizontalmente, el registro de `eventId` debería ser compartido
(Redis o una tabla con clave única).

## Imágenes

`eclipse-temurin:25-jre` + el jar, usuario sin privilegios (`spring`, uid 1001) y
`-XX:MaxRAMPercentage=75` para que la JVM respete la memoria del contenedor.
