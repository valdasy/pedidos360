package cl.duoc.pedidos360.notify.messaging;

import cl.duoc.pedidos360.notify.messaging.payload.EmailCommand;
import cl.duoc.pedidos360.notify.messaging.payload.InvoiceCommand;
import cl.duoc.pedidos360.notify.messaging.payload.KitchenTicketCommand;
import cl.duoc.pedidos360.notify.service.EmailService;
import cl.duoc.pedidos360.notify.service.InvoiceService;
import cl.duoc.pedidos360.notify.service.KitchenService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Consume las tres colas de comandos con ACK manual.
 *
 * Decisión por cada mensaje:
 *   OK                          -> basicAck            (se retira de la cola)
 *   duplicado (eventId repetido) -> basicAck            (no se repite el efecto)
 *   JSON ilegible o datos malos -> basicReject(false)  (error permanente -> DLQ)
 *   error transitorio, 1a vez   -> basicNack(requeue)  (se reintenta una vez)
 *   error transitorio, reintento-> basicReject(false)  (-> DLQ, lo revisa mq-admin)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventStore processedEvents;
    private final EmailService emailService;
    private final KitchenService kitchenService;
    private final InvoiceService invoiceService;

    @RabbitListener(queues = "q.cmd.email")
    public void onEmail(Message message, Channel channel) throws IOException {
        handle(message, channel, new TypeReference<CommandEnvelope<EmailCommand>>() { }, emailService::send);
    }

    @RabbitListener(queues = "q.cmd.kitchen")
    public void onKitchen(Message message, Channel channel) throws IOException {
        handle(message, channel, new TypeReference<CommandEnvelope<KitchenTicketCommand>>() { }, kitchenService::printTicket);
    }

    @RabbitListener(queues = "q.cmd.invoice")
    public void onInvoice(Message message, Channel channel) throws IOException {
        handle(message, channel, new TypeReference<CommandEnvelope<InvoiceCommand>>() { }, invoiceService::generate);
    }

    private <T> void handle(Message message, Channel channel,
                            TypeReference<CommandEnvelope<T>> type, Consumer<T> action) throws IOException {
        var props = message.getMessageProperties();
        long tag = props.getDeliveryTag();
        String queue = props.getConsumerQueue();

        // 1. Leer el JSON. Si no se puede leer, nunca se podrá: va directo a la DLQ.
        CommandEnvelope<T> envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), type);
        } catch (JacksonException e) {
            log.error("[{}] JSON inválido, se envía a la DLQ: {}", queue, e.getOriginalMessage());
            channel.basicReject(tag, false);
            return;
        }
        // JSON válido pero sin el formato de envelope (p. ej. publicado a mano desde la UI).
        if (envelope.eventId() == null || envelope.payload() == null) {
            log.error("[{}] Mensaje sin envelope (eventId/payload), se envía a la DLQ", queue);
            channel.basicReject(tag, false);
            return;
        }

        // 2. Idempotencia: el mismo eventId no se procesa dos veces.
        if (processedEvents.alreadyProcessed(envelope.eventId())) {
            log.warn("[{}] Duplicado ignorado eventId={}", queue, envelope.eventId());
            channel.basicAck(tag, false);
            return;
        }

        // 3. Procesar y decidir ACK / NACK / REJECT.
        try {
            action.accept(envelope.payload());
            processedEvents.markProcessed(envelope.eventId());
            channel.basicAck(tag, false);
            log.info("[{}] ACK eventId={} correlationId={}", queue, envelope.eventId(), envelope.correlationId());

        } catch (InvalidCommandException e) {
            log.error("[{}] Comando inválido, REJECT -> DLQ eventId={}: {}", queue, envelope.eventId(), e.getMessage());
            channel.basicReject(tag, false);

        } catch (RuntimeException e) {
            if (Boolean.TRUE.equals(props.getRedelivered())) {
                log.error("[{}] Falló de nuevo tras reintento, REJECT -> DLQ eventId={}: {}",
                        queue, envelope.eventId(), e.getMessage());
                channel.basicReject(tag, false);
            } else {
                log.warn("[{}] Error transitorio, NACK con reencolado eventId={}: {}",
                        queue, envelope.eventId(), e.getMessage());
                channel.basicNack(tag, false, true);
            }
        }
    }
}
