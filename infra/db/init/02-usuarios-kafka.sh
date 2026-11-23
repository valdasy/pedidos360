#!/bin/bash
# Usuarios de los consumidores Kafka (Evaluación 3): cada uno con su propio esquema.
# Se ejecuta solo al crear la base por primera vez. Si la base ya existía:
#   bash infra/db/crear-usuarios-kafka.sh
set -e

sqlplus -s / as sysdba <<EOF
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE USER pedidos_audit IDENTIFIED BY "${AUDIT_DB_PASSWORD}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO pedidos_audit;

CREATE USER pedidos_report IDENTIFIED BY "${REPORT_DB_PASSWORD}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO pedidos_report;

EXIT;
EOF

echo "Usuarios pedidos_audit y pedidos_report creados en FREEPDB1"
