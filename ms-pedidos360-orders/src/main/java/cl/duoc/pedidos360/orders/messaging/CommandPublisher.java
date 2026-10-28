package cl.duoc.pedidos360.orders.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publica comandos en RabbitMQ y espera la confirmación del broker.
 *
 * Sin confirmación, convertAndSend solo garantiza que el mensaje salió de la
 * aplicación, no que el broker lo haya guardado en una cola.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;

    /**
     * @param eventId puede ser null (se genera uno nuevo). Reenviar el mismo eventId
     *                simula un mensaje duplicado para probar la idempotencia del consumidor.
     */
    public <T> CommandEnvelope<T> publish(String exchange, String routingKey, CommandType type,
                                          T payload, String correlationId, UUID eventId) {
        CommandEnvelope<T> envelope = new CommandEnvelope<>(
                type.name(),
                eventId != null ? eventId : UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID().toString(),
                correlationId,
                payload);

        CorrelationData correlation = new CorrelationData(envelope.eventId().toString());

        rabbitTemplate.convertAndSend(exchange, routingKey, envelope, message -> {
            var props = message.getMessageProperties();
            props.setMessageId(envelope.eventId().toString());
            props.setCorrelationId(correlationId);
            props.setType(envelope.type());
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        }, correlation);

        waitForConfirm(correlation, exchange, routingKey);
        log.info("Comando {} publicado en {} [{}] eventId={} correlationId={}",
                type, exchange, routingKey, envelope.eventId(), correlationId);
        return envelope;
    }

    private void waitForConfirm(CorrelationData correlation, String exchange, String routingKey) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Publicación interrumpida", e);
        } catch (Exception e) {
            throw new PublishException("El broker no confirmó la publicación a tiempo", e);
        }

        if (!confirm.ack()) {
            throw new PublishException("El broker rechazó el mensaje: " + confirm.reason());
        }

        // Con mandatory=true el broker devuelve los mensajes que ninguna cola acepta.
        // El "return" llega antes que el "ack", así que aquí ya está disponible.
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new PublishException("Mensaje no enrutado: ninguna cola coincide con "
                    + exchange + " [" + routingKey + "] (" + returned.getReplyText() + ")");
        }
    }
}
