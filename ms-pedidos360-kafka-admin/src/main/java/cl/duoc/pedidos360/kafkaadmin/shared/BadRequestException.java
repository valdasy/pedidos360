package cl.duoc.pedidos360.kafkaadmin.shared;

public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
