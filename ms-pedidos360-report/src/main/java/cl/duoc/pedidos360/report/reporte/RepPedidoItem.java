package cl.duoc.pedidos360.report.reporte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@Entity
@Table(name = "rep_pedido_item")
@IdClass(RepPedidoItem.Pk.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepPedidoItem {

    @Id
    @ManyToOne
    @JoinColumn(name = "pedido_id")
    private RepPedido pedido;

    @Id
    @Column(name = "producto_id")
    private Long productoId;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private int cantidad;

    @Column(name = "precio_unitario", nullable = false)
    private long precioUnitario;

    RepPedidoItem(RepPedido pedido, Long productoId, String nombre, int cantidad, long precioUnitario) {
        this.pedido = pedido;
        this.productoId = productoId;
        this.nombre = nombre;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
    }

    public record Pk(Long pedido, Long productoId) implements Serializable {
    }
}
