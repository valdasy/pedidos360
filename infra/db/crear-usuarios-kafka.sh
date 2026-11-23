#!/usr/bin/env bash
# Crea pedidos_audit y pedidos_report en una base que YA existía (local o EC2 db).
# Idempotente: si el usuario existe, no hace nada.
#   cd infra/db && bash crear-usuarios-kafka.sh
set -euo pipefail
cd "$(dirname "$0")"
# Git Bash (Windows) convierte "/" en una ruta de Windows; esto lo evita (en Linux no tiene efecto)
export MSYS_NO_PATHCONV=1

# Lee AUDIT_DB_PASSWORD y REPORT_DB_PASSWORD del .env
set -a; source ./.env; set +a
: "${AUDIT_DB_PASSWORD:?Falta AUDIT_DB_PASSWORD en infra/db/.env}"
: "${REPORT_DB_PASSWORD:?Falta REPORT_DB_PASSWORD en infra/db/.env}"

docker exec -i pedidos360-oracle sqlplus -s / as sysdba <<EOF
ALTER SESSION SET CONTAINER = FREEPDB1;
SET SERVEROUTPUT ON
DECLARE
  PROCEDURE crear(p_user VARCHAR2, p_pass VARCHAR2) IS
    n NUMBER;
  BEGIN
    SELECT COUNT(*) INTO n FROM dba_users WHERE username = UPPER(p_user);
    IF n = 0 THEN
      EXECUTE IMMEDIATE 'CREATE USER ' || p_user || ' IDENTIFIED BY "' || p_pass || '" QUOTA UNLIMITED ON USERS';
      EXECUTE IMMEDIATE 'GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO ' || p_user;
      DBMS_OUTPUT.PUT_LINE('creado: ' || p_user);
    ELSE
      DBMS_OUTPUT.PUT_LINE('ya existía: ' || p_user);
    END IF;
  END;
BEGIN
  crear('pedidos_audit',  '${AUDIT_DB_PASSWORD}');
  crear('pedidos_report', '${REPORT_DB_PASSWORD}');
END;
/
EXIT;
EOF
