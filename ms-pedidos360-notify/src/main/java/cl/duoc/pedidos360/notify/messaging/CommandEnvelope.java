package cl.duoc.pedidos360.notify.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Mismo contrato JSON que el envelope de ms-orders, pero con clase propia:
 * los microservicios comparten el formato del mensaje, no el código.
 */
public record CommandEnvelope<T>(
        String type,
        UUID eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        T payload) {
}
