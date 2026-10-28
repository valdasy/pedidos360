package cl.duoc.pedidos360.orders.messaging.payload;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Ticket de preparación del pedido (cola q.cmd.kitchen). */
public record KitchenTicketCommand(
        @NotNull Long pedidoId,
        List<Item> productos) {

    public record Item(String nombre, int cantidad) {
    }
}
