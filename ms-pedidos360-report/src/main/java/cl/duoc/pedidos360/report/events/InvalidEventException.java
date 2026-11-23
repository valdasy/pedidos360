package cl.duoc.pedidos360.report.events;

/** Evento que no cumple el contrato: no se reintenta, va directo al DLT. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
