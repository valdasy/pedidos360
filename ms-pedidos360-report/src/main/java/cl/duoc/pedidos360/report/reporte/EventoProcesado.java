package cl.duoc.pedidos360.report.reporte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** eventId ya aplicado a los reportes (idempotencia). */
@Getter
@Entity
@Table(name = "rep_evento_procesado")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventoProcesado implements Persistable<String> {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(nullable = false)
    private String tipo;

    @Column(name = "procesado_en", nullable = false)
    private Instant procesadoEn;

    EventoProcesado(String eventId, String tipo) {
        this.eventId = eventId;
        this.tipo = tipo;
        this.procesadoEn = Instant.now();
    }

    @Override
    public String getId() {
        return eventId;
    }

    /** Siempre es nuevo: así save() hace INSERT directo (y un duplicado falla por PK) en vez de un merge. */
    @Override
    public boolean isNew() {
        return true;
    }
}
