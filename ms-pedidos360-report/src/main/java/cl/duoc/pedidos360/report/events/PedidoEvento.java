package cl.duoc.pedidos360.report.events;

import java.time.Instant;
import java.util.List;

/**
 * Copia LOCAL del contrato "pedidos.eventos" (lo publica ms-orders).
 * Cada microservicio tiene su propia copia del DTO: así no comparten código ni se acoplan.
 * "tipo" y los estados se reciben como String para tolerar valores nuevos que agregue el
 * productor sin que este consumidor falle (compatibilidad hacia adelante).
 */
public record PedidoEvento(
        String eventId,
        String tipo,
        Integer version,
        Long pedidoId,
        String estadoAnterior,
        String estado,
        String clienteNombre,
        String clienteEmail,
        Long total,
        List<Item> items,
        String usuario,
        Instant ocurridoEn,
        String origen) {

    public record Item(Long productoId, String nombre, int cantidad, long precioUnitario) {
    }

    /** Reglas mínimas del contrato. Si no se cumplen, el evento va al DLT sin reintentos. */
    public void validar() {
        Validaciones.eventId(eventId);
        Validaciones.requerido(tipo, "tipo");
        Validaciones.requerido(pedidoId, "pedidoId");
        Validaciones.requerido(estado, "estado");
        Validaciones.requerido(ocurridoEn, "ocurridoEn");
        Validaciones.requerido(total, "total");
        if (total < 0) {
            throw new InvalidEventException("total negativo: " + total);
        }
    }
}
