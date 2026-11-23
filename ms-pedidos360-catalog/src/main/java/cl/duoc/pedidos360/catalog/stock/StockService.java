package cl.duoc.pedidos360.catalog.stock;

import cl.duoc.pedidos360.catalog.events.InventarioEvento;
import cl.duoc.pedidos360.catalog.events.InventarioEventoTipo;
import cl.duoc.pedidos360.catalog.product.Product;
import org.springframework.context.ApplicationEventPublisher;

import static cl.duoc.pedidos360.catalog.events.InventarioEventPublisher.usuarioActual;
import cl.duoc.pedidos360.catalog.product.ProductRepository;
import cl.duoc.pedidos360.catalog.shared.ConflictException;
import cl.duoc.pedidos360.catalog.shared.NotFoundException;
import cl.duoc.pedidos360.catalog.stock.StockReservation.Estado;
import cl.duoc.pedidos360.catalog.stock.StockReservation.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Descuenta y devuelve stock por pedido. La regla del caso: "el stock decrece al aceptar el pedido".
 *
 * Todo ocurre en UNA transacción: o se descuentan todos los productos del pedido,
 * o ninguno (si falta stock de uno, se hace rollback del resto).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final ProductRepository products;
    private final StockReservationRepository reservations;
    /** Eventos internos: InventarioEventPublisher los envía a Kafka después del commit. */
    private final ApplicationEventPublisher events;

    public record InsufficientItem(Long productoId, String nombre, int solicitado, int disponible) {
    }

    @Transactional
    public StockReservation reserve(Long pedidoId, List<Item> requested) {
        var existing = reservations.findById(pedidoId);
        if (existing.isPresent()) {
            if (existing.get().getEstado() == Estado.RESERVADA) {
                return existing.get();   // idempotente: el reintento no descuenta dos veces
            }
            throw new ConflictException("La reserva del pedido " + pedidoId + " ya fue liberada");
        }

        // Agrupa productos repetidos y los ordena por id: bloquear siempre en el mismo
        // orden evita deadlocks entre dos pedidos que comparten productos.
        Map<Long, Integer> byProduct = new TreeMap<>();
        requested.forEach(i -> byProduct.merge(i.productoId(), i.cantidad(), Integer::sum));

        List<InsufficientItem> missing = new ArrayList<>();
        List<Product> locked = new ArrayList<>();
        for (var e : byProduct.entrySet()) {
            Product p = products.findByIdForUpdate(e.getKey())
                    .orElseThrow(() -> new NotFoundException("Producto " + e.getKey() + " no existe"));
            if (p.getStock() < e.getValue()) {
                missing.add(new InsufficientItem(p.getId(), p.getNombre(), e.getValue(), p.getStock()));
            }
            locked.add(p);
        }
        if (!missing.isEmpty()) {
            throw new InsufficientStockException(missing);
        }

        String usuario = usuarioActual();
        locked.forEach(p -> {
            int anterior = p.getStock();
            int cantidad = byProduct.get(p.getId());
            p.decreaseStock(cantidad);
            events.publishEvent(InventarioEvento.of(InventarioEventoTipo.STOCK_RESERVADO, p.getId(), p.getSku(),
                    p.getNombre(), pedidoId, cantidad, anterior, p.getStock(), p.getPrecio(), usuario));
        });
        List<Item> items = byProduct.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue())).toList();
        log.info("Stock reservado para pedido {}: {}", pedidoId, items);
        return reservations.save(new StockReservation(pedidoId, items));
    }

    /** Devuelve el stock (pedido cancelado). Idempotente: liberar dos veces no suma dos veces. */
    @Transactional
    public void release(Long pedidoId) {
        reservations.findById(pedidoId)
                .filter(r -> r.getEstado() == Estado.RESERVADA)
                .ifPresent(r -> {
                    String usuario = usuarioActual();
                    r.getItems().forEach(i -> products.findByIdForUpdate(i.productoId())
                            .ifPresent(p -> {
                                int anterior = p.getStock();
                                p.increaseStock(i.cantidad());
                                events.publishEvent(InventarioEvento.of(InventarioEventoTipo.STOCK_LIBERADO,
                                        p.getId(), p.getSku(), p.getNombre(), pedidoId, i.cantidad(), anterior,
                                        p.getStock(), p.getPrecio(), usuario));
                            }));
                    r.release();
                    log.info("Stock liberado para pedido {}", pedidoId);
                });
    }

    @Transactional(readOnly = true)
    public StockReservation get(Long pedidoId) {
        return reservations.findById(pedidoId)
                .orElseThrow(() -> new NotFoundException("No hay reserva para el pedido " + pedidoId));
    }
}
