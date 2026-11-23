package cl.duoc.pedidos360.audit.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Un evento de negocio tal como llegó desde Kafka. Solo se inserta: la auditoría no se edita.
 * Guarda tópico, partición y offset para poder ubicar el mensaje original en Kafka.
 */
@Getter
@Entity
@Table(name = "audit_evento")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UNIQUE en la tabla: la misma entrega repetida no crea dos filas (idempotencia). */
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(nullable = false)
    private String topico;

    @Column(nullable = false)
    private int particion;

    @Column(name = "offset_kafka", nullable = false)
    private long offset;

    @Column(nullable = false)
    private String tipo;

    /** PEDIDO o PRODUCTO */
    @Column(nullable = false)
    private String entidad;

    @Column(name = "entidad_id")
    private Long entidadId;

    /** Para armar el timeline de un pedido con eventos de ambos tópicos (p. ej. STOCK_RESERVADO). */
    @Column(name = "pedido_id")
    private Long pedidoId;

    private String descripcion;

    private String usuario;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant ocurridoEn;

    @Column(name = "recibido_en", nullable = false)
    private Instant recibidoEn;

    public AuditEvent(String eventId, String topico, int particion, long offset, String tipo, String entidad,
                      Long entidadId, Long pedidoId, String descripcion, String usuario, String payload,
                      Instant ocurridoEn) {
        this.eventId = eventId;
        this.topico = topico;
        this.particion = particion;
        this.offset = offset;
        this.tipo = tipo;
        this.entidad = entidad;
        this.entidadId = entidadId;
        this.pedidoId = pedidoId;
        this.descripcion = descripcion;
        this.usuario = usuario;
        this.payload = payload;
        this.ocurridoEn = ocurridoEn;
        this.recibidoEn = Instant.now();
    }
}
