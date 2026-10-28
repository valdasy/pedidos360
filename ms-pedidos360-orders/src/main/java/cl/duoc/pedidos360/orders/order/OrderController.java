package cl.duoc.pedidos360.orders.order;

import cl.duoc.pedidos360.orders.order.OrderDtos.OrderRequest;
import cl.duoc.pedidos360.orders.order.OrderDtos.OrderResponse;
import cl.duoc.pedidos360.orders.order.OrderDtos.StatusChangeResponse;
import cl.duoc.pedidos360.orders.order.OrderDtos.StatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Pedidos")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService service;

    @Operation(summary = "Listar pedidos (filtros opcionales: estado o email del cliente)")
    @GetMapping
    public List<OrderResponse> list(@RequestParam(required = false) OrderStatus estado,
                                    @RequestParam(required = false) String email) {
        return service.list(estado, email);
    }

    @Operation(summary = "Obtener un pedido")
    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Historial de estados del pedido")
    @GetMapping("/{id}/history")
    public List<StatusChangeResponse> history(@PathVariable Long id) {
        return service.history(id);
    }

    @Operation(summary = "Crear pedido (precios y nombres se toman de ms-catalog)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@Valid @RequestBody OrderRequest request) {
        return service.create(request);
    }

    @Operation(summary = "Editar pedido (solo en estado CREADO)")
    @PutMapping("/{id}")
    public OrderResponse update(@PathVariable Long id, @Valid @RequestBody OrderRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "Eliminar pedido (solo en estado CREADO)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @Operation(summary = "Cambiar estado: CREADO -> ACEPTADO -> EN_PREPARACION -> DESPACHADO -> ENTREGADO | CANCELADO")
    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request.estado());
    }
}
