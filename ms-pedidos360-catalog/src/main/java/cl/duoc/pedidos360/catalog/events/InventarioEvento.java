package cl.duoc.pedidos360.catalog.events;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO tipado del tópico Kafka "inventario.eventos" (lo consume ms-audit).
 * Un evento por producto afectado. Clave = productoId: los movimientos de un mismo
 * producto quedan en la misma partición y se leen en orden.
 *
 * @param pedidoId     solo en STOCK_RESERVADO / STOCK_LIBERADO
 * @param cantidad     unidades que se movieron (0 en eventos de producto)
 */
public record InventarioEvento(
        String eventId,
        InventarioEventoTipo tipo,
        int version,
        Long productoId,
        String sku,
        String nombre,
        Long pedidoId,
        int cantidad,
        Integer stockAnterior,
        int stockNuevo,
        long precio,
        String usuario,
        Instant ocurridoEn,
        String origen) {

    public static final int VERSION = 1;

    public static InventarioEvento of(InventarioEventoTipo tipo, Long productoId, String sku, String nombre,
                                      Long pedidoId, int cantidad, Integer stockAnterior, int stockNuevo,
                                      long precio, String usuario) {
        return new InventarioEvento(UUID.randomUUID().toString(), tipo, VERSION, productoId, sku, nombre,
                pedidoId, cantidad, stockAnterior, stockNuevo, precio, usuario, Instant.now(),
                "ms-pedidos360-catalog");
    }
}
