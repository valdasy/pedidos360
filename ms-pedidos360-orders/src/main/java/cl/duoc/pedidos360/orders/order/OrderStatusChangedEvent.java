package cl.duoc.pedidos360.orders.order;

import java.time.Instant;
import java.util.List;

/**
 * Evento interno de Spring (no viaja tal cual por ningún broker): "el pedido cambió de estado".
 * Lleva una copia de los datos para no depender de la sesión de base de datos
 * cuando se procesa después del commit.
 *
 * Lo escuchan dos componentes, ambos AFTER_COMMIT:
 *  - OrderCommandDispatcher -> comandos RabbitMQ (correo, cocina, boleta, nota de crédito)
 *  - PedidoEventPublisher   -> evento de negocio en Kafka (pedidos.eventos) para audit y report
 *
 * @param estadoAnterior null cuando el pedido se acaba de crear
 * @param usuario        email del usuario del token (null si la seguridad está desactivada)
 */
public record OrderStatusChangedEvent(
        Long pedidoId,
        OrderStatus estadoAnterior,
        OrderStatus estado,
        String clienteNombre,
        String clienteEmail,
        long total,
        List<Item> items,
        String usuario,
        Instant ocurridoEn) {

    public record Item(Long productoId, String nombre, int cantidad, long precioUnitario) {
    }

    static OrderStatusChangedEvent of(Order o, OrderStatus estadoAnterior, String usuario) {
        return new OrderStatusChangedEvent(o.getId(), estadoAnterior, o.getEstado(), o.getClienteNombre(),
                o.getClienteEmail(), o.getTotal(), o.getItems().stream()
                        .map(i -> new Item(i.getProductoId(), i.getNombre(), i.getCantidad(), i.getPrecioUnitario()))
                        .toList(),
                usuario, Instant.now());
    }
}
