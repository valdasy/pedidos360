package cl.duoc.pedidos360.report.reporte;

import cl.duoc.pedidos360.report.events.PedidoEvento;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Proyección de un pedido para reportes, construida solo con eventos de "pedidos.eventos". */
@Getter
@Entity
@Table(name = "rep_pedido")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepPedido {

    @Id
    @Column(name = "pedido_id")
    private Long pedidoId;

    @Column(nullable = false)
    private String estado;

    @Column(nullable = false)
    private long total;

    @Column(name = "cliente_email")
    private String clienteEmail;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "aceptado_en")
    private Instant aceptadoEn;

    @Column(name = "entregado_en")
    private Instant entregadoEn;

    @Column(name = "cancelado_en")
    private Instant canceladoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    // SUBSELECT: al leer todos los pedidos, sus ítems se cargan en UNA consulta extra
    // (y no una por pedido, el clásico problema N+1).
    @Fetch(FetchMode.SUBSELECT)
    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<RepPedidoItem> items = new ArrayList<>();

    /** Primer evento que se conoce del pedido (normalmente PEDIDO_CREADO). */
    static RepPedido desde(PedidoEvento e) {
        RepPedido p = new RepPedido();
        p.pedidoId = e.pedidoId();
        p.clienteEmail = e.clienteEmail();
        p.creadoEn = e.ocurridoEn();
        if (e.items() != null) {
            e.items().forEach(i -> p.items.add(
                    new RepPedidoItem(p, i.productoId(), i.nombre(), i.cantidad(), i.precioUnitario())));
        }
        p.aplicar(e);
        return p;
    }

    /** Aplica un cambio de estado: actualiza estado, total y la marca de tiempo de cada hito. */
    void aplicar(PedidoEvento e) {
        estado = e.estado();
        total = e.total();
        actualizadoEn = e.ocurridoEn();
        switch (e.estado()) {
            case "ACEPTADO" -> aceptadoEn = e.ocurridoEn();
            case "ENTREGADO" -> entregadoEn = e.ocurridoEn();
            case "CANCELADO" -> canceladoEn = e.ocurridoEn();
            default -> { }
        }
    }

    /** Ventas "confirmadas": el pedido fue aceptado (stock descontado) y no se canceló. */
    public boolean esVentaConfirmada() {
        return switch (estado) {
            case "ACEPTADO", "EN_PREPARACION", "DESPACHADO", "ENTREGADO" -> true;
            default -> false;
        };
    }
}
