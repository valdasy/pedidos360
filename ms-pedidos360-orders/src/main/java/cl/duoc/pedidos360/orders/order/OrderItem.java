package cl.duoc.pedidos360.orders.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pedido_items")
@Getter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pedido_id")
    private Order order;

    @Column(name = "producto_id")
    private Long productoId;

    private String nombre;

    @Column(name = "precio_unitario")
    private long precioUnitario;

    private int cantidad;

    public OrderItem(Long productoId, String nombre, long precioUnitario, int cantidad) {
        this.productoId = productoId;
        this.nombre = nombre;
        this.precioUnitario = precioUnitario;
        this.cantidad = cantidad;
    }

    public long getSubtotal() {
        return precioUnitario * cantidad;
    }
}
