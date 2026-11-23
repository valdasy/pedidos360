package cl.duoc.pedidos360.orders.events;

import cl.duoc.pedidos360.orders.order.OrderStatus;
import cl.duoc.pedidos360.orders.order.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Productor Kafka: publica cada cambio de un pedido en "pedidos.eventos".
 *
 *  - AFTER_COMMIT: solo se publica lo que quedó guardado en Oracle.
 *  - Clave = pedidoId: todos los eventos de un pedido caen en la MISMA partición, así
 *    ms-report los recibe en orden (CREADO antes que ENTREGADO).
 *  - acks=all + idempotencia del productor (application.properties): el broker confirma
 *    cuando el evento está en todas las réplicas en sincronía, y un reintento de red no
 *    duplica el mensaje en la partición.
 *  - Si Kafka no está disponible, el pedido NO falla (ya está guardado): se registra el
 *    error. Igual que con RabbitMQ, la solución completa sería un Transactional Outbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PedidoEventPublisher {

    public static final String TOPICO = "pedidos.eventos";

    private final KafkaTemplate<String, PedidoEvento> kafka;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderStatusChangedEvent e) {
        PedidoEvento evento = toEvento(e);
        try {
            kafka.send(TOPICO, String.valueOf(e.pedidoId()), evento).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka: no se pudo publicar {} del pedido {} (eventId={}): {}",
                            evento.tipo(), e.pedidoId(), evento.eventId(), ex.getMessage());
                } else {
                    var meta = result.getRecordMetadata();
                    log.info("Kafka: {} pedido {} -> {}-{} offset {} (eventId={})", evento.tipo(),
                            e.pedidoId(), meta.topic(), meta.partition(), meta.offset(), evento.eventId());
                }
            });
        } catch (RuntimeException ex) {
            // p. ej. sin brokers: send() espera la metadata hasta max.block.ms y luego lanza
            log.error("Kafka: no se pudo publicar {} del pedido {}: {}", evento.tipo(), e.pedidoId(), ex.getMessage());
        }
    }

    static PedidoEvento toEvento(OrderStatusChangedEvent e) {
        PedidoEventoTipo tipo = e.estadoAnterior() == null ? PedidoEventoTipo.PEDIDO_CREADO
                : e.estado() == OrderStatus.CANCELADO ? PedidoEventoTipo.PEDIDO_CANCELADO
                : PedidoEventoTipo.PEDIDO_ESTADO_CAMBIADO;
        return new PedidoEvento(
                UUID.randomUUID().toString(), tipo, PedidoEvento.VERSION, e.pedidoId(),
                e.estadoAnterior() == null ? null : e.estadoAnterior().name(), e.estado().name(),
                e.clienteNombre(), e.clienteEmail(), e.total(),
                e.items().stream().map(i -> new PedidoEvento.Item(i.productoId(), i.nombre(), i.cantidad(),
                        i.precioUnitario())).toList(),
                e.usuario(), e.ocurridoEn(), "ms-pedidos360-orders");
    }
}
