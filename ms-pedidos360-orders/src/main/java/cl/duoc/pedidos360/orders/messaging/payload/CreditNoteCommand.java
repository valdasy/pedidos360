package cl.duoc.pedidos360.orders.messaging.payload;

import java.util.List;

/** Generación de la nota de crédito de un pedido cancelado (cola q.cmd.invoice). */
public record CreditNoteCommand(
        Long pedidoId,
        String cliente,
        String email,
        long monto,
        String motivo,
        List<Item> items) {

    public record Item(String nombre, int cantidad, long precioUnitario) {
    }
}
