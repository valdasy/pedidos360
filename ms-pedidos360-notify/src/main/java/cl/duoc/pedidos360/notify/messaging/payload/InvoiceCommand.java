package cl.duoc.pedidos360.notify.messaging.payload;

public record InvoiceCommand(Long pedidoId, String cliente, long monto, String documento) {
}
