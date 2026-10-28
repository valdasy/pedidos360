package cl.duoc.pedidos360.catalog.product;

import cl.duoc.pedidos360.catalog.product.ProductDtos.ProductRequest;
import cl.duoc.pedidos360.catalog.product.ProductDtos.ProductResponse;
import cl.duoc.pedidos360.catalog.shared.ConflictException;
import cl.duoc.pedidos360.catalog.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository repository;

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
        return ProductResponse.from(repository.save(p));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest req) {
        Product p = get(id);
        if (!p.getSku().equals(req.sku()) && repository.existsBySku(req.sku())) {
            throw new ConflictException("Ya existe un producto con SKU " + req.sku());
        }
        apply(p, req);
        return ProductResponse.from(p);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(get(id));
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
