package cl.duoc.pedidos360.orders.messaging.payload;

import jakarta.validation.constraints.NotNull;

/** Generación de boleta o factura (cola q.cmd.invoice). */
public record InvoiceCommand(
        @NotNull Long pedidoId,
        String cliente,
        long monto,
        String documento) {
}
