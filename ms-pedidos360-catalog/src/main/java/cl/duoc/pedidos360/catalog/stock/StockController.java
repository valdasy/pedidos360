package cl.duoc.pedidos360.catalog.stock;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Usado por ms-orders (llamada síncrona) al aceptar o cancelar un pedido. */
@Tag(name = "Stock (uso interno de ms-orders)")
@RestController
@RequestMapping("/api/catalog/stock/reservations")
@RequiredArgsConstructor
public class StockController {

    private final StockService service;

    public record ItemRequest(@NotNull Long productoId, @Positive int cantidad) {
    }

    public record ReservationRequest(@NotNull Long pedidoId, @NotEmpty List<@Valid ItemRequest> items) {
    }

    public record ReservationResponse(Long pedidoId, String estado, List<StockReservation.Item> items) {

        static ReservationResponse from(StockReservation r) {
            return new ReservationResponse(r.getPedidoId(), r.getEstado().name(), r.getItems());
        }
    }

    @Operation(summary = "Descontar stock de un pedido (todo o nada). 409 si falta stock")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@Valid @RequestBody ReservationRequest req) {
        var items = req.items().stream()
                .map(i -> new StockReservation.Item(i.productoId(), i.cantidad())).toList();
        return ReservationResponse.from(service.reserve(req.pedidoId(), items));
    }

    @Operation(summary = "Consultar la reserva de un pedido")
    @GetMapping("/{pedidoId}")
    public ReservationResponse get(@PathVariable Long pedidoId) {
        return ReservationResponse.from(service.get(pedidoId));
    }

    @Operation(summary = "Devolver el stock de un pedido cancelado")
    @DeleteMapping("/{pedidoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable Long pedidoId) {
        service.release(pedidoId);
    }
}
