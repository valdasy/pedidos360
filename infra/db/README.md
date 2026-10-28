# infra/db — Oracle (solo desarrollo local)

Oracle Database Free 23 en Docker, para desarrollar sin gastar crédito de AWS.
En AWS se usa **Amazon RDS for Oracle** con los mismos usuarios: solo cambia `DB_HOST`.

## Levantar

```bash
cp .env.example .env     # y cambiar las claves
docker compose up -d
docker compose logs -f   # esperar "DATABASE IS READY TO USE!" (1-2 min la primera vez)
```

Si ya tienes **Oracle XE instalado en Windows**, ocupa el puerto 1521: pon `DB_PORT=1522`
en `.env`. Los microservicios leen el mismo `.env` y usan ese puerto.

## Usuarios (database per service)

| Usuario / esquema | Microservicio | Tablas |
|---|---|---|
| `pedidos_orders` | ms-orders | `pedidos`, `pedido_items`, `pedido_historial` |
| `pedidos_catalog` | ms-catalog | `productos`, `reservas_stock`, `reserva_items` |

Los crea `init/01-usuarios.sh` la primera vez. Las **tablas** las crea cada
microservicio con **Flyway** al arrancar (`src/main/resources/db/migration`).

Cada servicio solo accede a su esquema. Si orders necesita datos del catálogo, los
pide por la API de ms-catalog.

## Conectarse con SQL Developer / DBeaver

- Host `localhost`, puerto `DB_PORT` (1521 o 1522), Service name `FREEPDB1`
- Usuario `pedidos_orders` o `pedidos_catalog`, clave del `.env`
