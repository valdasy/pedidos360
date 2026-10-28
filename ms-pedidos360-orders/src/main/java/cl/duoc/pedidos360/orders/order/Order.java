package cl.duoc.pedidos360.orders.order;

import cl.duoc.pedidos360.orders.shared.ConflictException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pedidos")
@Getter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cliente_nombre")
    private String clienteNombre;

    @Column(name = "cliente_email")
    private String clienteEmail;

    @Enumerated(EnumType.STRING)
    private OrderStatus estado;

    private long total;

    @Version
    private long version;

    @Column(name = "creado_en")
    private Instant creadoEn;
    @Column(name = "actualizado_en")
    private Instant actualizadoEn;
    @Column(name = "aceptado_en")
    private Instant aceptadoEn;
    @Column(name = "entregado_en")
    private Instant entregadoEn;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("cambiadoEn")
    private List<OrderStatusChange> historial = new ArrayList<>();

    public Order(String clienteNombre, String clienteEmail) {
        this.clienteNombre = clienteNombre;
        this.clienteEmail = clienteEmail;
        this.estado = OrderStatus.CREADO;
        this.creadoEn = Instant.now();
        this.actualizadoEn = creadoEn;
        this.historial.add(new OrderStatusChange(this, null, OrderStatus.CREADO));
    }

    /** Reemplaza cliente e ítems. Solo mientras el pedido no ha sido aceptado. */
    public void update(String clienteNombre, String clienteEmail, List<OrderItem> newItems) {
        if (estado != OrderStatus.CREADO) {
            throw new ConflictException("Solo se puede editar un pedido en estado CREADO (actual: " + estado + ")");
        }
        this.clienteNombre = clienteNombre;
        this.clienteEmail = clienteEmail;
        replaceItems(newItems);
        this.actualizadoEn = Instant.now();
    }

    public void replaceItems(List<OrderItem> newItems) {
        items.clear();
        newItems.forEach(i -> {
            i.setOrder(this);
            items.add(i);
        });
        total = items.stream().mapToLong(OrderItem::getSubtotal).sum();
    }

    /** Aplica la máquina de estados. Lanza ConflictException si la transición no está permitida. */
    public OrderStatus changeStatus(OrderStatus target) {
        if (!estado.canMoveTo(target)) {
            throw new ConflictException("Transición no permitida: " + estado + " -> " + target
                    + ". Desde " + estado + " se puede ir a " + estado.next());
        }
        OrderStatus previous = estado;
        estado = target;
        actualizadoEn = Instant.now();
        if (target == OrderStatus.ACEPTADO) {
            aceptadoEn = actualizadoEn;
        } else if (target == OrderStatus.ENTREGADO) {
            entregadoEn = actualizadoEn;
        }
        historial.add(new OrderStatusChange(this, previous, target));
        return previous;
    }
}
