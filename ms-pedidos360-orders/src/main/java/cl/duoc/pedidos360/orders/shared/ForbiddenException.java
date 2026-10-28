package cl.duoc.pedidos360.orders.shared;

/** El usuario está autenticado pero no puede operar sobre este recurso (403). */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
