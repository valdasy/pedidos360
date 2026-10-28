package cl.duoc.pedidos360.orders.order;

import cl.duoc.pedidos360.orders.catalog.CatalogClient;
import cl.duoc.pedidos360.orders.order.OrderDtos.ItemRequest;
import cl.duoc.pedidos360.orders.order.OrderDtos.OrderRequest;
import cl.duoc.pedidos360.orders.order.OrderDtos.OrderResponse;
import cl.duoc.pedidos360.orders.order.OrderDtos.StatusChangeResponse;
import cl.duoc.pedidos360.orders.shared.ConflictException;
import cl.duoc.pedidos360.orders.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final OrderRepository repository;
    private final CatalogClient catalog;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public List<OrderResponse> list(OrderStatus estado, String email) {
        List<Order> orders;
        if (estado != null) {
            orders = repository.findByEstado(estado, NEWEST_FIRST);
        } else if (email != null && !email.isBlank()) {
            orders = repository.findByClienteEmailIgnoreCase(email, NEWEST_FIRST);
        } else {
            orders = repository.findAll(NEWEST_FIRST);
        }
        return orders.stream().map(OrderResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return OrderResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public List<StatusChangeResponse> history(Long id) {
        return find(id).getHistorial().stream()
                .map(h -> new StatusChangeResponse(h.getEstadoAnterior(), h.getEstadoNuevo(), h.getCambiadoEn()))
                .toList();
    }

    @Transactional
    public OrderResponse create(OrderRequest req) {
        Order order = new Order(req.clienteNombre(), req.clienteEmail());
        order.replaceItems(toItems(req.items()));
        repository.save(order);
        events.publishEvent(OrderStatusChangedEvent.of(order));
        log.info("Pedido {} creado: total ${}", order.getId(), order.getTotal());
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse update(Long id, OrderRequest req) {
        Order order = find(id);
        order.update(req.clienteNombre(), req.clienteEmail(), toItems(req.items()));
        return OrderResponse.from(order);
    }

    @Transactional
    public void delete(Long id) {
        Order order = find(id);
        if (order.getEstado() != OrderStatus.CREADO) {
            throw new ConflictException("Solo se puede eliminar un pedido CREADO; en otro estado debe cancelarse");
        }
        repository.delete(order);
    }

    /**
     * Cambia el estado aplicando la máquina de estados y los efectos de negocio:
     *  - ACEPTADO:  descuenta stock en ms-catalog (síncrono; si falta stock, 409 y no cambia).
     *  - CANCELADO: si el stock ya estaba descontado, lo devuelve.
     * Después del commit, OrderCommandDispatcher publica los comandos en RabbitMQ.
     */
    @Transactional
    public OrderResponse changeStatus(Long id, OrderStatus target) {
        Order order = find(id);
        OrderStatus previous = order.getEstado();
        order.changeStatus(target);   // valida la transición antes de llamar a catalog

        if (target == OrderStatus.ACEPTADO) {
            catalog.reserve(order.getId(), order.getItems().stream()
                    .map(i -> new CatalogClient.ReservationItem(i.getProductoId(), i.getCantidad())).toList());
        } else if (target == OrderStatus.CANCELADO && previous.hasReservedStock()) {
            catalog.release(order.getId());
        }

        repository.flush();   // fuerza el UPDATE ahora: si falla (p. ej. edición concurrente), no se publica nada
        events.publishEvent(OrderStatusChangedEvent.of(order));
        log.info("Pedido {}: {} -> {}", id, previous, target);
        return OrderResponse.from(order);
    }

    private Order find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Pedido " + id + " no existe"));
    }

    /** Toma nombre y precio vigentes del catálogo (el cliente no puede inventar precios). */
    private List<OrderItem> toItems(List<ItemRequest> items) {
        return items.stream().map(i -> {
            CatalogClient.Product p = catalog.getProduct(i.productoId());
            return new OrderItem(p.id(), p.nombre(), p.precio(), i.cantidad());
        }).toList();
    }
}
