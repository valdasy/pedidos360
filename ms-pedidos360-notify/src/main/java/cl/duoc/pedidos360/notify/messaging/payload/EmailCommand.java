package cl.duoc.pedidos360.notify.messaging.payload;

public record EmailCommand(Long pedidoId, String email, String asunto, String mensaje) {
}
