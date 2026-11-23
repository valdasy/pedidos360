package cl.duoc.pedidos360.catalog.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Productor Kafka de "inventario.eventos".
 *
 * Los servicios publican un InventarioEvento como evento INTERNO de Spring dentro de su
 * transacción; este componente lo envía a Kafka solo DESPUÉS del commit. Si la transacción
 * hace rollback (p. ej. falta stock), no sale ningún evento de un movimiento que no ocurrió.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventarioEventPublisher {

    public static final String TOPICO = "inventario.eventos";

    private final KafkaTemplate<String, InventarioEvento> kafka;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(InventarioEvento evento) {
        try {
            kafka.send(TOPICO, String.valueOf(evento.productoId()), evento).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka: no se pudo publicar {} del producto {} (eventId={}): {}",
                            evento.tipo(), evento.productoId(), evento.eventId(), ex.getMessage());
                } else {
                    var meta = result.getRecordMetadata();
                    log.info("Kafka: {} producto {} -> {}-{} offset {}", evento.tipo(), evento.productoId(),
                            meta.topic(), meta.partition(), meta.offset());
                }
            });
        } catch (RuntimeException ex) {
            log.error("Kafka: no se pudo publicar {} del producto {}: {}",
                    evento.tipo(), evento.productoId(), ex.getMessage());
        }
    }

    /** Email del usuario del token, para la auditoría (null si la seguridad está desactivada). */
    public static String usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            String u = token.getToken().getClaimAsString("preferred_username");
            return u != null ? u : token.getName();
        }
        return null;
    }
}
