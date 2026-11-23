package cl.duoc.pedidos360.audit.events;

import java.time.Instant;

/** Copia LOCAL del contrato "inventario.eventos" (lo publica ms-catalog). */
public record InventarioEvento(
        String eventId,
        String tipo,
        Integer version,
        Long productoId,
        String sku,
        String nombre,
        Long pedidoId,
        Integer cantidad,
        Integer stockAnterior,
        Integer stockNuevo,
        Long precio,
        String usuario,
        Instant ocurridoEn,
        String origen) {

    public void validar() {
        Validaciones.eventId(eventId);
        Validaciones.requerido(tipo, "tipo");
        Validaciones.requerido(productoId, "productoId");
        Validaciones.requerido(ocurridoEn, "ocurridoEn");
        Validaciones.requerido(cantidad, "cantidad");
        Validaciones.requerido(stockNuevo, "stockNuevo");
        Validaciones.requerido(precio, "precio");
        if (cantidad < 0 || stockNuevo < 0) {
            throw new InvalidEventException("cantidad o stock negativo");
        }
    }
}
