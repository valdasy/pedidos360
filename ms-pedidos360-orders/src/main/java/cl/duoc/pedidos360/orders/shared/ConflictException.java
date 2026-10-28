package cl.duoc.pedidos360.orders.shared;

import java.util.Map;

/** Regla de negocio violada (409): transición inválida, stock insuficiente, etc. */
public class ConflictException extends RuntimeException {

    private final transient Map<String, Object> details;

    public ConflictException(String message) {
        this(message, Map.of());
    }

    public ConflictException(String message, Map<String, Object> details) {
        super(message);
        this.details = details;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
