package cl.duoc.pedidos360.catalog.product;

import cl.duoc.pedidos360.catalog.events.InventarioEvento;
import cl.duoc.pedidos360.catalog.events.InventarioEventoTipo;
import cl.duoc.pedidos360.catalog.product.ProductDtos.ProductRequest;
import cl.duoc.pedidos360.catalog.product.ProductDtos.ProductResponse;
import cl.duoc.pedidos360.catalog.shared.ConflictException;
import cl.duoc.pedidos360.catalog.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static cl.duoc.pedidos360.catalog.events.InventarioEventPublisher.usuarioActual;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository repository;
    /** Eventos internos: InventarioEventPublisher los envía a Kafka después del commit. */
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public List<ProductResponse> findAll() {
        return repository.findAll(Sort.by("nombre")).stream().map(ProductResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return ProductResponse.from(get(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest req) {
        if (repository.existsBySku(req.sku())) {
            throw new ConflictException("Ya existe un producto con SKU " + req.sku());
        }
        Product p = new Product();
        apply(p, req);
        repository.saveAndFlush(p);   // flush: asigna el id antes de publicar el evento
        events.publishEvent(evento(InventarioEventoTipo.PRODUCTO_CREADO, p, null));
        return ProductResponse.from(p);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest req) {
        Product p = get(id);
        if (!p.getSku().equals(req.sku()) && repository.existsBySku(req.sku())) {
            throw new ConflictException("Ya existe un producto con SKU " + req.sku());
        }
        int stockAnterior = p.getStock();
        apply(p, req);
        events.publishEvent(evento(InventarioEventoTipo.PRODUCTO_ACTUALIZADO, p, stockAnterior));
        return ProductResponse.from(p);
    }

    @Transactional
    public void delete(Long id) {
        Product p = get(id);
        repository.delete(p);
        events.publishEvent(evento(InventarioEventoTipo.PRODUCTO_ELIMINADO, p, p.getStock()));
    }

    private static InventarioEvento evento(InventarioEventoTipo tipo, Product p, Integer stockAnterior) {
        return InventarioEvento.of(tipo, p.getId(), p.getSku(), p.getNombre(), null, 0, stockAnterior,
                p.getStock(), p.getPrecio(), usuarioActual());
    }

    private Product get(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Producto " + id + " no existe"));
    }

    private void apply(Product p, ProductRequest req) {
        p.setSku(req.sku());
        p.setNombre(req.nombre());
        p.setDescripcion(req.descripcion());
        p.setPrecio(req.precio());
        p.setStock(req.stock());
    }
}
