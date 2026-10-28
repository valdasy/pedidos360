package cl.duoc.pedidos360.orders.messaging.payload;

import jakarta.validation.constraints.NotNull;

/** Datos para enviar un correo al cliente (cola q.cmd.email). */
public record EmailCommand(
        @NotNull Long pedidoId,
        String email,
        String asunto,
        String mensaje) {
}
