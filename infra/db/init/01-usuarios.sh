#!/bin/bash
# Crea un usuario (= esquema) por microservicio en la base FREEPDB1.
# "Database per service": orders no puede leer las tablas de catalog y viceversa;
# si orders necesita datos del catálogo, los pide por la API de catalog.
set -e

sqlplus -s / as sysdba <<EOF
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE USER pedidos_orders IDENTIFIED BY "${ORDERS_DB_PASSWORD}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO pedidos_orders;

CREATE USER pedidos_catalog IDENTIFIED BY "${CATALOG_DB_PASSWORD}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO pedidos_catalog;

EXIT;
EOF

echo "Usuarios pedidos_orders y pedidos_catalog creados en FREEPDB1"
