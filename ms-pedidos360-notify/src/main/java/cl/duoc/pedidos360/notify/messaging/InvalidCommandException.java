package cl.duoc.pedidos360.notify.messaging;

/**
 * Error permanente: el comando tiene datos inválidos y reintentarlo no servirá.
 * El mensaje se rechaza sin reencolar y termina en la DLQ.
 */
public class InvalidCommandException extends RuntimeException {

    public InvalidCommandException(String message) {
        super(message);
    }
}
