package cl.duoc.pedidos360.catalog.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Stock descontado para un pedido. Permite devolverlo si el pedido se cancela. */
@Entity
@Table(name = "reservas_stock")
@Getter
@NoArgsConstructor
public class StockReservation {

    public enum Estado { RESERVADA, LIBERADA }

    @Id
    @Column(name = "pedido_id")
    private Long pedidoId;

    @Enumerated(EnumType.STRING)
    private Estado estado;

    @Column(name = "creada_en", insertable = false, updatable = false)
    private Instant creadaEn;

    @ElementCollection
    @CollectionTable(name = "reserva_items", joinColumns = @JoinColumn(name = "pedido_id"))
    private List<Item> items = new ArrayList<>();

    public StockReservation(Long pedidoId, List<Item> items) {
        this.pedidoId = pedidoId;
        this.items = new ArrayList<>(items);
        this.estado = Estado.RESERVADA;
    }

    public void release() {
        this.estado = Estado.LIBERADA;
    }

    @Embeddable
    public record Item(@Column(name = "producto_id") Long productoId, int cantidad) {
    }
}
