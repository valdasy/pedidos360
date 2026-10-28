package cl.duoc.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Sobre (envelope) común para todos los comandos que viajan por RabbitMQ.
 *
 * @param type          tipo de comando, p. ej. EMAIL_SEND. El consumidor decide cómo procesarlo.
 * @param eventId       identificador único del mensaje. El consumidor lo usa para la idempotencia.
 * @param timestamp     momento en que se creó el comando.
 * @param traceId       identifica la petición HTTP de origen (para seguirla en los logs).
 * @param correlationId agrupa todos los mensajes de un mismo flujo de negocio (p. ej. un pedido).
 * @param payload       datos propios del comando.
 */
public record CommandEnvelope<T>(
        String type,
        UUID eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        T payload) {
}
