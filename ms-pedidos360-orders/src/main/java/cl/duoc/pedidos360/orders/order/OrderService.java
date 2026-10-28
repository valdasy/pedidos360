package cl.duoc.pedidos360.orders.order;

import cl.duoc.pedidos360.orders.catalog.CatalogClient;
import cl.duoc.pedidos360.orders.order.OrderDtos.ItemRequest;
import cl.duoc.pedidos360.orders.order.OrderDtos.OrderRequest;
import cl.duoc.pedidos360.orders.order.OrderDtos.OrderResponse;
import cl.duoc.pedidos360.orders.order.OrderDtos.StatusChangeResponse;
import cl.duoc.pedidos360.orders.security.CurrentUser;
import cl.duoc.pedidos360.orders.shared.ConflictException;
import cl.duoc.pedidos360.orders.shared.ForbiddenException;
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
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public List<OrderResponse> list(OrderStatus estado, String email) {
        // Un Cliente solo ve sus propios pedidos, sin importar qué filtro envíe
        var cliente = currentUser.get().filter(CurrentUser.User::isOnlyCliente);
        if (cliente.isPresent()) {
            return repository.findByClienteEmailIgnoreCase(cliente.get().email(), NEWEST_FIRST).stream()
                    .filter(o -> estado == null || o.getEstado() == estado)
                    .map(OrderResponse::from).toList();
        }
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
        return OrderResponse.from(findAccessible(id));
    }

    @Transactional(readOnly = true)
    public List<StatusChangeResponse> history(Long id) {
        return findAccessible(id).getHistorial().stream()
                .map(h -> new StatusChangeResponse(h.getEstadoAnterior(), h.getEstadoNuevo(), h.getCambiadoEn()))
                .toList();
    }

    @Transactional
    public OrderResponse create(OrderRequest req) {
        // Un Cliente siempre compra a su nombre: el email sale del token, no del formulario
        String email = currentUser.get().filter(CurrentUser.User::isOnlyCliente)
                .map(CurrentUser.User::email).orElse(req.clienteEmail());
        Order order = new Order(req.clienteNombre(), email);
        order.replaceItems(toItems(req.items()));
        repository.save(order);
        events.publishEvent(OrderStatusChangedEvent.of(order));
        log.info("Pedido {} creado: total ${}", order.getId(), order.getTotal());
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse update(Long id, OrderRequest req) {
        Order order = findAccessible(id);
        String email = currentUser.get().filter(CurrentUser.User::isOnlyCliente)
                .map(CurrentUser.User::email).orElse(req.clienteEmail());
        order.update(req.clienteNombre(), email, toItems(req.items()));
        return OrderResponse.from(order);
    }

    @Transactional
    public void delete(Long id) {
        Order order = findAccessible(id);
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
        Order order = findAccessible(id);
        if (currentUser.get().filter(CurrentUser.User::isOnlyCliente).isPresent()
                && (target != OrderStatus.CANCELADO || order.getEstado() != OrderStatus.CREADO)) {
            throw new ForbiddenException("Un cliente solo puede cancelar sus pedidos que aún no han sido aceptados");
        }
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

    /** Como find, pero un Cliente solo accede a sus propios pedidos. */
    private Order findAccessible(Long id) {
        Order order = find(id);
        currentUser.get().filter(CurrentUser.User::isOnlyCliente).ifPresent(u -> {
            if (!order.getClienteEmail().equalsIgnoreCase(u.email())) {
                throw new ForbiddenException("El pedido " + id + " no te pertenece");
            }
        });
        return order;
    }

    /** Toma nombre y precio vigentes del catálogo (el cliente no puede inventar precios). */
    private List<OrderItem> toItems(List<ItemRequest> items) {
        return items.stream().map(i -> {
            CatalogClient.Product p = catalog.getProduct(i.productoId());
            return new OrderItem(p.id(), p.nombre(), p.precio(), i.cantidad());
        }).toList();
    }
}
