package cl.duoc.pedidos360.notify.messaging.payload;

import java.util.List;

public record KitchenTicketCommand(Long pedidoId, List<Item> productos) {

    public record Item(String nombre, int cantidad) {
    }
}
