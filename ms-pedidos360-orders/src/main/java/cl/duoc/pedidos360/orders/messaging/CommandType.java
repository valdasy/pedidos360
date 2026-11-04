package cl.duoc.pedidos360.orders.messaging;

/**
 * Los tres flujos de trabajo de Pedidos360, con su routing key base.
 * La routing key coincide con los bindings definidos en infra/mq/definitions.json.
 */
public enum CommandType {

    EMAIL_SEND("email.send"),
    KITCHEN_TICKET("kitchen.ticket"),
    INVOICE_GEN("invoice.gen"),
    /**
     * Nota de crédito al cancelar un pedido. Es una variante del flujo de facturación:
     * se publica en cmd.topic y el binding "invoice.*" la lleva a q.cmd.invoice,
     * sin crear colas ni bindings nuevos.
     */
    CREDIT_NOTE_GEN("invoice.credit");

    private final String routingKey;

    CommandType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
