#!/usr/bin/env bash
# Carga el historial de pedidos de ejemplo en Oracle (esquema pedidos_orders).
# Ejecutar en la máquina donde corre el contenedor pedidos360-oracle (local o ec2-db):
#   bash scripts/historial/cargar-historial-db.sh [dias]
# Idempotente: si el historial ya está cargado, no hace nada.
# Entra como SYS por el sistema operativo del contenedor (/ as sysdba): no pide claves.
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git Bash (Windows): no convertir "/" en ruta de Windows

python3 generar_historial.py --dias "${1:-90}" --salida . 2>/dev/null \
  || python generar_historial.py --dias "${1:-90}" --salida .
docker cp historial-orders.sql pedidos360-oracle:/tmp/historial-orders.sql >/dev/null
docker exec pedidos360-oracle sqlplus -s / as sysdba @/tmp/historial-orders.sql
