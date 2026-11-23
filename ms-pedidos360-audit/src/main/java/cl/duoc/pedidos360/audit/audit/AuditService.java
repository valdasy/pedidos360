package cl.duoc.pedidos360.audit.audit;

import cl.duoc.pedidos360.audit.audit.AuditDtos.AuditEventResponse;
import cl.duoc.pedidos360.audit.audit.AuditDtos.ResumenResponse;
import cl.duoc.pedidos360.audit.events.InventarioEvento;
import cl.duoc.pedidos360.audit.events.PedidoEvento;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lógica de negocio de la auditoría, separada del listener de Kafka:
 * el listener solo recibe y delega; aquí se valida, se describe y se guarda.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;
    private final JsonMapper json;

    /** @return false si el evento ya estaba registrado (entrega duplicada) */
    public boolean registrarPedido(ConsumerRecord<String, PedidoEvento> record) {
        PedidoEvento e = record.value();
        e.validar();
        String descripcion = e.estadoAnterior() == null
                ? "Pedido #%d creado por $%,d (%d ítems)".formatted(e.pedidoId(), e.total(),
                        e.items() == null ? 0 : e.items().size())
                : "Pedido #%d: %s → %s".formatted(e.pedidoId(), e.estadoAnterior(), e.estado());
        return guardar(new AuditEvent(e.eventId(), record.topic(), record.partition(), record.offset(), e.tipo(),
                "PEDIDO", e.pedidoId(), e.pedidoId(), descripcion, e.usuario(), json.writeValueAsString(e),
                e.ocurridoEn()));
    }

    public boolean registrarInventario(ConsumerRecord<String, InventarioEvento> record) {
        InventarioEvento e = record.value();
        e.validar();
        String descripcion = switch (e.tipo()) {
            case "STOCK_RESERVADO" -> "%s: -%d unidades por el pedido #%d (stock %d → %d)"
                    .formatted(e.nombre(), e.cantidad(), e.pedidoId(), e.stockAnterior(), e.stockNuevo());
            case "STOCK_LIBERADO" -> "%s: +%d unidades devueltas del pedido #%d (stock %d → %d)"
                    .formatted(e.nombre(), e.cantidad(), e.pedidoId(), e.stockAnterior(), e.stockNuevo());
            case "PRODUCTO_CREADO" -> "Producto %s (%s) creado: precio $%,d, stock %d"
                    .formatted(e.nombre(), e.sku(), e.precio(), e.stockNuevo());
            case "PRODUCTO_ELIMINADO" -> "Producto %s (%s) eliminado".formatted(e.nombre(), e.sku());
            default -> "Producto %s (%s) actualizado: precio $%,d, stock %s → %d".formatted(e.nombre(), e.sku(),
                    e.precio(), e.stockAnterior(), e.stockNuevo());
        };
        return guardar(new AuditEvent(e.eventId(), record.topic(), record.partition(), record.offset(), e.tipo(),
                "PRODUCTO", e.productoId(), e.pedidoId(), descripcion, e.usuario(), json.writeValueAsString(e),
                e.ocurridoEn()));
    }

    /**
     * Idempotencia: Kafka garantiza "al menos una vez". Si el consumidor procesa un evento
     * pero se cae antes de confirmar el offset, al volver lo recibe de nuevo. El eventId
     * (UNIQUE en la tabla) evita guardarlo dos veces.
     * Sin @Transactional: save() tiene su propia transacción, así un duplicado detectado por
     * la restricción UNIQUE no deja la transacción marcada para rollback.
     */
    private boolean guardar(AuditEvent event) {
        if (repository.existsByEventId(event.getEventId())) {
            log.info("Evento duplicado ignorado: {} ({})", event.getEventId(), event.getTipo());
            return false;
        }
        try {
            repository.save(event);
        } catch (DataIntegrityViolationException dup) {
            log.info("Evento duplicado ignorado (carrera): {}", event.getEventId());
            return false;
        }
        log.info("Auditado {}-{} offset {}: {} {}", event.getTopico(), event.getParticion(), event.getOffset(),
                event.getTipo(), event.getDescripcion());
        return true;
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> buscar(String entidad, Long entidadId, String tipo, String usuario, int limit) {
        return repository.search(blankToNull(entidad), entidadId, blankToNull(tipo), blankToNull(usuario),
                PageRequest.of(0, Math.clamp(limit, 1, 500))).stream().map(AuditEventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> timeline(Long pedidoId) {
        return repository.findByPedidoIdOrderByOcurridoEnAscIdAsc(pedidoId).stream()
                .map(AuditEventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ResumenResponse resumen() {
        Map<String, Long> porTipo = new LinkedHashMap<>();
        repository.countByTipo().forEach(r -> porTipo.put((String) r[0], (Long) r[1]));
        return new ResumenResponse(repository.count(), porTipo);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
