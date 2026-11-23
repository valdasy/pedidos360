package cl.duoc.pedidos360.audit.events;

import java.util.UUID;

final class Validaciones {

    private Validaciones() {
    }

    static void requerido(Object valor, String campo) {
        if (valor == null || (valor instanceof String s && s.isBlank())) {
            throw new InvalidEventException("falta el campo obligatorio '" + campo + "'");
        }
    }

    /** El eventId es la base de la idempotencia: debe existir y ser un UUID. */
    static void eventId(String eventId) {
        requerido(eventId, "eventId");
        try {
            UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("eventId no es un UUID: " + eventId);
        }
    }
}
