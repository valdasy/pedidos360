"""
Genera un HISTORIAL de pedidos de ejemplo (últimos N días) para que los reportes tengan datos.

Produce dos archivos con los MISMOS pedidos:
  - historial-orders.sql    -> se carga en Oracle (esquema pedidos_orders): pedidos, ítems, historial
  - historial-eventos.txt   -> un evento por línea "clave|json", para publicar en Kafka
                               (pedidos.eventos). Lo leen ms-audit y ms-report, igual que en vivo.

Es determinista: con la misma fecha de hoy y la misma semilla, genera exactamente lo mismo.
Por eso se puede ejecutar por separado en la EC2 de la base y en la de Kafka (el mismo día)
y los dos lados quedan coherentes. Los eventId también son fijos: si se publica dos veces,
los consumidores lo ignoran (idempotencia).

Uso:  python3 generar_historial.py [--dias 90] [--salida .]
"""
import argparse
import json
import random
import uuid
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

try:
    from zoneinfo import ZoneInfo
    CHILE = ZoneInfo("America/Santiago")
except Exception:  # Windows sin tzdata: hora de verano de Chile
    CHILE = timezone(timedelta(hours=-3))

# Ids reservados para el historial: no chocan con los pedidos reales (que parten en 1).
ID_INICIAL = 10001
NS = uuid.UUID("6f1c3a52-0d6e-4f0a-9a43-3c6b0e5f3601")

# Catálogo inicial (V2__datos_iniciales.sql de ms-catalog): id, nombre, precio, popularidad
PRODUCTOS = [
    (1, "Café americano", 2500, 30),
    (2, "Cappuccino", 3200, 24),
    (3, "Medialuna", 1200, 22),
    (4, "Torta de chocolate", 3900, 9),
    (5, "Sándwich ave palta", 4500, 12),
    (6, "Jugo natural", 2800, 8),
]

CLIENTES = [
    ("Camila Rojas", "camila.rojas@ejemplo.cl"), ("Matías González", "matias.gonzalez@ejemplo.cl"),
    ("Valentina Muñoz", "valentina.munoz@ejemplo.cl"), ("Benjamín Soto", "benjamin.soto@ejemplo.cl"),
    ("Isidora Díaz", "isidora.diaz@ejemplo.cl"), ("Joaquín Pérez", "joaquin.perez@ejemplo.cl"),
    ("Florencia Silva", "florencia.silva@ejemplo.cl"), ("Tomás Contreras", "tomas.contreras@ejemplo.cl"),
    ("Antonia Morales", "antonia.morales@ejemplo.cl"), ("Vicente Araya", "vicente.araya@ejemplo.cl"),
    ("Martina Fuentes", "martina.fuentes@ejemplo.cl"), ("Agustín Torres", "agustin.torres@ejemplo.cl"),
    ("Josefa Castillo", "josefa.castillo@ejemplo.cl"), ("Lucas Espinoza", "lucas.espinoza@ejemplo.cl"),
    ("Emilia Reyes", "emilia.reyes@ejemplo.cl"), ("Maximiliano Vargas", "maximiliano.vargas@ejemplo.cl"),
    ("Sofía Herrera", "sofia.herrera@ejemplo.cl"), ("Diego Carrasco", "diego.carrasco@ejemplo.cl"),
]

# Pedidos base por día de la semana (lunes..domingo) y horas punta (desayuno y almuerzo)
BASE_DIA = [7, 7, 8, 8, 10, 12, 5]
HORAS = [8] * 6 + [9] * 8 + [10] * 6 + [11] * 3 + [12] * 4 + [13] * 7 + [14] * 5 + [15] * 2 + \
        [16] * 3 + [17] * 4 + [18] * 3 + [19] * 2
FLUJO = ["CREADO", "ACEPTADO", "EN_PREPARACION", "DESPACHADO", "ENTREGADO"]
OPERADOR = "operador.historial@pedidos360.cl"


def generar(dias: int, hoy: date):
    # Solo días COMPLETOS (hasta ayer): así el resultado depende solo de la fecha, no de la hora,
    # y "hoy" queda libre para los pedidos de la demo en vivo.
    rnd = random.Random(f"pedidos360-{hoy.isoformat()}")
    pedidos = []
    pid = ID_INICIAL
    for d in range(dias, 0, -1):  # desde hace "dias" días hasta ayer
        dia = hoy - timedelta(days=d)
        crecimiento = 1 + 0.006 * (dias - d)  # el negocio crece ~0,6 % por día
        cantidad = max(1, round(rnd.gauss(BASE_DIA[dia.weekday()] * crecimiento, 1.6)))
        for _ in range(cantidad):
            creado = datetime.combine(dia, time(rnd.choice(HORAS), rnd.randrange(60), rnd.randrange(60)),
                                      tzinfo=CHILE)
            nombre, email = rnd.choice(CLIENTES)
            # Productos según su popularidad (sin repetir dentro del pedido)
            disponibles = list(PRODUCTOS)
            elegidos = []
            for _ in range(rnd.choices([1, 2, 3], weights=[45, 40, 15])[0]):
                p = rnd.choices(disponibles, weights=[x[3] for x in disponibles])[0]
                disponibles.remove(p)
                elegidos.append(p)
            items = [(p[0], p[1], p[2], rnd.choices([1, 2, 3], weights=[65, 28, 7])[0]) for p in elegidos]
            total = sum(precio * cant for _, _, precio, cant in items)

            # Destino: la mayoría se entrega; ~7 % se cancela
            r = rnd.random()
            if r < 0.04:
                destino, cancelar_en = "CANCELADO", 0      # cancelado antes de aceptar
            elif r < 0.07:
                destino, cancelar_en = "CANCELADO", 1      # cancelado después de aceptar
            else:
                destino, cancelar_en = "ENTREGADO", None

            pasos = []  # (estado, instante)
            t = creado
            pasos.append(("CREADO", t))
            ultimo = 0 if cancelar_en == 0 else (1 if cancelar_en == 1 else 4)
            for i in range(1, ultimo + 1):
                t += timedelta(minutes=[0, rnd.randint(1, 6), rnd.randint(3, 10), rnd.randint(5, 15),
                                        rnd.randint(10, 35)][i])
                pasos.append((FLUJO[i], t))
            if cancelar_en is not None:
                t += timedelta(minutes=rnd.randint(2, 20))
                pasos.append(("CANCELADO", t))

            pedidos.append({"id": pid, "nombre": nombre, "email": email, "items": items, "total": total,
                            "pasos": pasos})
            pid += 1
    return pedidos


def utc(t: datetime) -> datetime:
    return t.astimezone(timezone.utc)


def sql_ts(t: datetime) -> str:
    # TIMESTAMP sin zona: Hibernate guarda los Instant en UTC (hibernate.jdbc.time_zone=UTC)
    return f"TIMESTAMP '{utc(t).strftime('%Y-%m-%d %H:%M:%S')}'"


def iso(t: datetime) -> str:
    return utc(t).strftime("%Y-%m-%dT%H:%M:%SZ")


def esc(s: str) -> str:
    return s.replace("'", "''")


def escribir_sql(pedidos, ruta: Path):
    hasta = pedidos[-1]["id"] if pedidos else ID_INICIAL
    with ruta.open("w", encoding="utf-8", newline="\n") as f:
        f.write(f"""-- Historial de ejemplo generado por generar_historial.py ({len(pedidos)} pedidos)
-- Se ejecuta como SYS: el esquema se fija con CURRENT_SCHEMA.
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET FEEDBACK OFF
SET DEFINE OFF
SET SERVEROUTPUT ON
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = pedidos_orders;
-- Si ya se cargó, se detiene aquí sin tocar nada (sale con código 0).
WHENEVER SQLERROR EXIT 0 ROLLBACK
DECLARE n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM pedidos WHERE id BETWEEN {ID_INICIAL} AND {ID_INICIAL + 99999};
  IF n > 0 THEN
    DBMS_OUTPUT.PUT_LINE('El historial ya estaba cargado (' || n || ' pedidos). No se hizo nada.');
    RAISE_APPLICATION_ERROR(-20001, 'ya cargado');
  END IF;
END;
/
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
""")
        for p in pedidos:
            pasos = p["pasos"]
            estado, actualizado = pasos[-1]
            creado = pasos[0][1]
            aceptado = next((t for e, t in pasos if e == "ACEPTADO"), None)
            entregado = next((t for e, t in pasos if e == "ENTREGADO"), None)
            f.write(
                "INSERT INTO pedidos (id, cliente_nombre, cliente_email, estado, total, version, creado_en, "
                "actualizado_en, aceptado_en, entregado_en) VALUES "
                f"({p['id']}, '{esc(p['nombre'])}', '{p['email']}', '{estado}', {p['total']}, {len(pasos) - 1}, "
                f"{sql_ts(creado)}, {sql_ts(actualizado)}, {sql_ts(aceptado) if aceptado else 'NULL'}, "
                f"{sql_ts(entregado) if entregado else 'NULL'});\n")
            for prod_id, nombre, precio, cant in p["items"]:
                f.write("INSERT INTO pedido_items (pedido_id, producto_id, nombre, precio_unitario, cantidad) "
                        f"VALUES ({p['id']}, {prod_id}, '{esc(nombre)}', {precio}, {cant});\n")
            anterior = None
            for e, t in pasos:
                f.write("INSERT INTO pedido_historial (pedido_id, estado_anterior, estado_nuevo, cambiado_en) "
                        f"VALUES ({p['id']}, {repr(anterior) if anterior else 'NULL'}, '{e}', {sql_ts(t)});\n")
                anterior = e
        f.write(f"""COMMIT;
-- Los próximos pedidos reales siguen después del último id del historial
ALTER TABLE pedidos MODIFY id GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE);
BEGIN DBMS_OUTPUT.PUT_LINE('Historial cargado: {len(pedidos)} pedidos (ids {ID_INICIAL}-{hasta}).'); END;
/
EXIT
""")


def escribir_eventos(pedidos, ruta: Path):
    n = 0
    with ruta.open("w", encoding="utf-8", newline="\n") as f:
        for p in pedidos:
            anterior = None
            for estado, t in p["pasos"]:
                tipo = ("PEDIDO_CREADO" if anterior is None
                        else "PEDIDO_CANCELADO" if estado == "CANCELADO" else "PEDIDO_ESTADO_CAMBIADO")
                evento = {
                    "eventId": str(uuid.uuid5(NS, f"{p['id']}-{estado}")),
                    "tipo": tipo, "version": 1, "pedidoId": p["id"],
                    "estadoAnterior": anterior, "estado": estado,
                    "clienteNombre": p["nombre"], "clienteEmail": p["email"], "total": p["total"],
                    "items": [{"productoId": i, "nombre": nom, "cantidad": c, "precioUnitario": pr}
                              for i, nom, pr, c in p["items"]],
                    "usuario": p["email"] if anterior is None or estado == "CANCELADO" and anterior == "CREADO"
                    else OPERADOR,
                    "ocurridoEn": iso(t), "origen": "carga-historial",
                }
                f.write(f"{p['id']}|{json.dumps(evento, ensure_ascii=False, separators=(',', ':'))}\n")
                anterior = estado
                n += 1
    return n


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dias", type=int, default=90)
    ap.add_argument("--salida", default=str(Path(__file__).parent))
    a = ap.parse_args()
    hoy = datetime.now(CHILE).date()
    pedidos = generar(a.dias, hoy)
    out = Path(a.salida)
    escribir_sql(pedidos, out / "historial-orders.sql")
    n = escribir_eventos(pedidos, out / "historial-eventos.txt")
    print(f"{len(pedidos)} pedidos ({hoy - timedelta(days=a.dias)} a {hoy - timedelta(days=1)}), "
          f"{n} eventos -> {out}")


if __name__ == "__main__":
    main()
