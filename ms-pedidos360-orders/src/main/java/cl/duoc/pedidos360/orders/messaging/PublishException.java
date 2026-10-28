package cl.duoc.pedidos360.orders.messaging;

/** El broker no confirmó el mensaje o no lo pudo enrutar a ninguna cola. */
public class PublishException extends RuntimeException {

    public PublishException(String message) {
        super(message);
    }

    public PublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
