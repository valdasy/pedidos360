package cl.duoc.pedidos360.orders.order;

import java.util.List;

/**
 * Evento interno de Spring (no viaja por RabbitMQ): "el pedido cambió de estado".
 * Lleva una copia de los datos para no depender de la sesión de base de datos
 * cuando se procesa después del commit.
 */
public record OrderStatusChangedEvent(
        Long pedidoId,
        OrderStatus estado,
        String clienteNombre,
        String clienteEmail,
        long total,
        List<Item> items) {

    public record Item(String nombre, int cantidad) {
    }

    static OrderStatusChangedEvent of(Order o) {
        return new OrderStatusChangedEvent(o.getId(), o.getEstado(), o.getClienteNombre(), o.getClienteEmail(),
                o.getTotal(), o.getItems().stream().map(i -> new Item(i.getNombre(), i.getCantidad())).toList());
    }
}
