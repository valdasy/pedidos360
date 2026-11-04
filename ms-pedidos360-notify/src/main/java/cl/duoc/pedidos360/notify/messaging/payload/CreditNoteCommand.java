package cl.duoc.pedidos360.notify.messaging.payload;

import java.util.List;

public record CreditNoteCommand(Long pedidoId, String cliente, String email, long monto, String motivo,
                                List<Item> items) {

    public record Item(String nombre, int cantidad, long precioUnitario) {
    }
}
