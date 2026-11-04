package cl.duoc.pedidos360.orders.messaging;

import cl.duoc.pedidos360.orders.messaging.payload.CreditNoteCommand;
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
 *   ENTREGADO         -> INVOICE_GEN     (q.cmd.invoice, vía cmd.direct [invoice.gen])
 *   CANCELADO         -> CREDIT_NOTE_GEN (q.cmd.invoice, vía cmd.topic [invoice.credit])
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
        publish(e, Exchanges.DIRECT, CommandType.EMAIL_SEND, email(e), correlationId);

        switch (e.estado()) {
            case ACEPTADO -> publish(e, Exchanges.DIRECT, CommandType.KITCHEN_TICKET,
                    new KitchenTicketCommand(e.pedidoId(), e.items().stream()
                            .map(i -> new KitchenTicketCommand.Item(i.nombre(), i.cantidad())).toList()),
                    correlationId);
            case ENTREGADO -> publish(e, Exchanges.DIRECT, CommandType.INVOICE_GEN,
                    new InvoiceCommand(e.pedidoId(), e.clienteNombre(), e.total(), "BOLETA"), correlationId);
            // cmd.topic: "invoice.credit" calza con el binding "invoice.*" -> q.cmd.invoice
            case CANCELADO -> publish(e, Exchanges.TOPIC, CommandType.CREDIT_NOTE_GEN,
                    new CreditNoteCommand(e.pedidoId(), e.clienteNombre(), e.clienteEmail(), e.total(),
                            "Anulación del pedido #" + e.pedidoId(),
                            e.items().stream().map(i -> new CreditNoteCommand.Item(
                                    i.nombre(), i.cantidad(), i.precioUnitario())).toList()),
                    correlationId);
            default -> { }
        }
    }

    /**
     * Cada comando se publica por separado: si uno falla (p. ej. su cola vive en un nodo
     * del clúster que está caído), los demás igual se intentan. Comprobado: con el nodo 1
     * caído, el correo falla pero la boleta (cola en el nodo 2) sí sale.
     */
    private void publish(OrderStatusChangedEvent e, String exchange, CommandType type, Object payload,
                         String correlationId) {
        try {
            publisher.publish(exchange, type.routingKey(), type, payload, correlationId, null);
        } catch (RuntimeException ex) {
            log.error("Pedido {} quedó {} pero no se pudo publicar {}: {}",
                    e.pedidoId(), e.estado(), type, ex.getMessage());
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
