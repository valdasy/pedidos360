package cl.duoc.pedidos360.orders.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record ItemRequest(@NotNull Long productoId, @Positive int cantidad) {
    }

    public record OrderRequest(
            @NotBlank @Size(max = 120) String clienteNombre,
            @NotBlank @Email @Size(max = 200) String clienteEmail,
            @NotEmpty List<@Valid ItemRequest> items) {
    }

    public record StatusRequest(@NotNull OrderStatus estado) {
    }

    public record ItemResponse(Long productoId, String nombre, long precioUnitario, int cantidad, long subtotal) {
    }

    public record StatusChangeResponse(OrderStatus estadoAnterior, OrderStatus estadoNuevo, Instant cambiadoEn) {
    }

    public record OrderResponse(
            Long id, String clienteNombre, String clienteEmail, OrderStatus estado,
            List<OrderStatus> siguientesEstados, long total, List<ItemResponse> items,
            Instant creadoEn, Instant actualizadoEn, Instant aceptadoEn, Instant entregadoEn) {

        static OrderResponse from(Order o) {
            return new OrderResponse(
                    o.getId(), o.getClienteNombre(), o.getClienteEmail(), o.getEstado(),
                    o.getEstado().next().stream().sorted().toList(), o.getTotal(),
                    o.getItems().stream().map(i -> new ItemResponse(
                            i.getProductoId(), i.getNombre(), i.getPrecioUnitario(), i.getCantidad(), i.getSubtotal())).toList(),
                    o.getCreadoEn(), o.getActualizadoEn(), o.getAceptadoEn(), o.getEntregadoEn());
        }
    }
}
