package cl.duoc.pedidos360.orders.messaging;

/**
 * Los tres flujos de trabajo de Pedidos360, con su routing key base.
 * La routing key coincide con los bindings definidos en infra/mq/definitions.json.
 */
public enum CommandType {

    EMAIL_SEND("email.send"),
    KITCHEN_TICKET("kitchen.ticket"),
    INVOICE_GEN("invoice.gen");

    private final String routingKey;

    CommandType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
