package cl.duoc.pedidos360.audit.audit;

import java.time.Instant;
import java.util.Map;

public final class AuditDtos {

    private AuditDtos() {
    }

    public record AuditEventResponse(Long id, String eventId, String tipo, String entidad, Long entidadId,
                                     Long pedidoId, String descripcion, String usuario, Instant ocurridoEn,
                                     Instant recibidoEn, String topico, int particion, long offset) {

        static AuditEventResponse from(AuditEvent a) {
            return new AuditEventResponse(a.getId(), a.getEventId(), a.getTipo(), a.getEntidad(), a.getEntidadId(),
                    a.getPedidoId(), a.getDescripcion(), a.getUsuario(), a.getOcurridoEn(), a.getRecibidoEn(),
                    a.getTopico(), a.getParticion(), a.getOffset());
        }
    }

    public record ResumenResponse(long totalEventos, Map<String, Long> porTipo) {
    }
}
