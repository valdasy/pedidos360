package cl.duoc.pedidos360.catalog.product;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "productos")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sku;
    private String nombre;
    private String descripcion;
    private long precio;
    private int stock;

    /** Bloqueo optimista: si dos personas editan el mismo producto a la vez, la segunda falla. */
    @Version
    private long version;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en")
    private Instant actualizadoEn;

    @PrePersist
    @PreUpdate
    void touch() {
        actualizadoEn = Instant.now();
    }

    public void decreaseStock(int cantidad) {
        if (cantidad > stock) {
            throw new IllegalStateException("Stock insuficiente");
        }
        stock -= cantidad;
    }

    public void increaseStock(int cantidad) {
        stock += cantidad;
    }
}
