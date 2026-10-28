package cl.duoc.pedidos360.orders.messaging;

import cl.duoc.pedidos360.orders.messaging.payload.EmailCommand;
import cl.duoc.pedidos360.orders.messaging.payload.InvoiceCommand;
import cl.duoc.pedidos360.orders.messaging.payload.KitchenTicketCommand;
import cl.duoc.pedidos360.orders.order.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Traduce cada cambio de estado del pedido a comandos RabbitMQ:
 *
 *   cualquier estado  -> EMAIL_SEND      (q.cmd.email)
 *   ACEPTADO          -> KITCHEN_TICKET  (q.cmd.kitchen)
 *   ENTREGADO         -> INVOICE_GEN     (q.cmd.invoice)
 *
 * AFTER_COMMIT: solo se publica si el cambio quedó guardado en Oracle. Si la
 * transacción hace rollback, no sale ningún correo de un pedido que no existe.
 *
 * Limitación conocida: si RabbitMQ falla justo después del commit, el pedido queda
 * guardado pero el comando se pierde (se registra en el log). La solución completa es
 * el patrón "Transactional Outbox": guardar el comando en una tabla en la misma
 * transacción y publicarlo desde ahí con reintentos.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCommandDispatcher {

    private final CommandPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderStatusChangedEvent e) {
        String correlationId = "pedido-" + e.pedidoId();
        try {
            publisher.publish(Exchanges.DIRECT, CommandType.EMAIL_SEND.routingKey(), CommandType.EMAIL_SEND,
                    email(e), correlationId, null);

            switch (e.estado()) {
                case ACEPTADO -> publisher.publish(Exchanges.DIRECT, CommandType.KITCHEN_TICKET.routingKey(),
                        CommandType.KITCHEN_TICKET, new KitchenTicketCommand(e.pedidoId(), e.items().stream()
                                .map(i -> new KitchenTicketCommand.Item(i.nombre(), i.cantidad())).toList()),
                        correlationId, null);
                case ENTREGADO -> publisher.publish(Exchanges.DIRECT, CommandType.INVOICE_GEN.routingKey(),
                        CommandType.INVOICE_GEN, new InvoiceCommand(e.pedidoId(), e.clienteNombre(), e.total(), "BOLETA"),
                        correlationId, null);
                default -> { }
            }
        } catch (RuntimeException ex) {
            log.error("Pedido {} quedó {} pero no se pudieron publicar sus comandos: {}",
                    e.pedidoId(), e.estado(), ex.getMessage());
        }
    }

    private EmailCommand email(OrderStatusChangedEvent e) {
        String asunto = switch (e.estado()) {
            case CREADO -> "Recibimos tu pedido #" + e.pedidoId();
            case ACEPTADO -> "Tu pedido #" + e.pedidoId() + " fue aceptado";
            case EN_PREPARACION -> "Tu pedido #" + e.pedidoId() + " está en preparación";
            case DESPACHADO -> "Tu pedido #" + e.pedidoId() + " va en camino";
            case ENTREGADO -> "Tu pedido #" + e.pedidoId() + " fue entregado";
            case CANCELADO -> "Tu pedido #" + e.pedidoId() + " fue cancelado";
        };
        String mensaje = "Hola " + e.clienteNombre() + ",\n\n" + asunto + ".\nTotal: $" + e.total()
                + "\n\nGracias por comprar en Pedidos360.";
        return new EmailCommand(e.pedidoId(), e.clienteEmail(), asunto, mensaje);
    }
}
