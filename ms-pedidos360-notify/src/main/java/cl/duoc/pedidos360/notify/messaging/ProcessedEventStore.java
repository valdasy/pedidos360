package cl.duoc.pedidos360.notify.messaging;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Registro de eventIds ya procesados, para la idempotencia: si RabbitMQ entrega
 * dos veces el mismo mensaje, el segundo se confirma sin repetir el efecto
 * (por ejemplo, sin enviar dos correos).
 *
 * Limitación: vive en memoria, se pierde al reiniciar y no se comparte entre
 * varias instancias de notify. En producción se usaría Redis o una tabla con
 * clave única.
 */
@Component
public class ProcessedEventStore {

    private static final int MAX_ENTRIES = 10_000;

    // LinkedHashMap con removeEldestEntry = caché que descarta los ids más antiguos.
    private final Set<UUID> processed = Collections.newSetFromMap(Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > MAX_ENTRIES;
                }
            }));

    public boolean alreadyProcessed(UUID eventId) {
        return processed.contains(eventId);
    }

    public void markProcessed(UUID eventId) {
        processed.add(eventId);
    }
}
