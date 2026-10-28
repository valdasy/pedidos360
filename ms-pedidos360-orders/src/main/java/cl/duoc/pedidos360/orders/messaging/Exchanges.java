package cl.duoc.pedidos360.orders.messaging;

/** Nombres de los exchanges creados en infra/mq/definitions.json. */
public final class Exchanges {

    /** Enrutamiento exacto por routing key. */
    public static final String DIRECT = "cmd.direct";
    /** Enrutamiento por patrones (variantes como email.send.high). */
    public static final String TOPIC = "cmd.topic";

    private Exchanges() {
    }
}
