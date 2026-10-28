package cl.duoc.pedidos360.catalog.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record ProductRequest(
            @NotBlank @Size(max = 40) String sku,
            @NotBlank @Size(max = 120) String nombre,
            @Size(max = 500) String descripcion,
            @PositiveOrZero long precio,
            @PositiveOrZero int stock) {
    }

    public record ProductResponse(Long id, String sku, String nombre, String descripcion,
                                  long precio, int stock, Instant actualizadoEn) {

        static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getSku(), p.getNombre(), p.getDescripcion(),
                    p.getPrecio(), p.getStock(), p.getActualizadoEn());
        }
    }
}
