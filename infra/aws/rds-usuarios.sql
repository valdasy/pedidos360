-- =========================================================
-- Crea los usuarios (esquemas) de Pedidos360 en Amazon RDS for Oracle.
-- Equivalente a infra/db/init/01-usuarios.sh, pero para RDS.
-- Ejecutar conectado con el usuario maestro de RDS (ver docs/etapa8-despliegue-aws.md):
--   @rds-usuarios.sql
-- Pedirá las dos claves (las mismas que pondrás en infra/apps/.env).
-- =========================================================
ACCEPT orders_pwd  CHAR PROMPT 'Clave para PEDIDOS_ORDERS: '  HIDE
ACCEPT catalog_pwd CHAR PROMPT 'Clave para PEDIDOS_CATALOG: ' HIDE

CREATE USER pedidos_orders IDENTIFIED BY "&orders_pwd" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO pedidos_orders;

CREATE USER pedidos_catalog IDENTIFIED BY "&catalog_pwd" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO pedidos_catalog;

SELECT username, created FROM dba_users WHERE username LIKE 'PEDIDOS%';
