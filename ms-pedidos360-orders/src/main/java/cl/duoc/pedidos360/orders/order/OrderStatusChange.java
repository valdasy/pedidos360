package cl.duoc.pedidos360.orders.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "pedido_historial")
@Getter
@NoArgsConstructor
public class OrderStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pedido_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_anterior")
    private OrderStatus estadoAnterior;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_nuevo")
    private OrderStatus estadoNuevo;

    @Column(name = "cambiado_en")
    private Instant cambiadoEn;

    OrderStatusChange(Order order, OrderStatus estadoAnterior, OrderStatus estadoNuevo) {
        this.order = order;
        this.estadoAnterior = estadoAnterior;
        this.estadoNuevo = estadoNuevo;
        this.cambiadoEn = Instant.now();
    }
}
