package cl.duoc.pedidos360.audit.consumer;

import cl.duoc.pedidos360.audit.audit.AuditService;
import cl.duoc.pedidos360.audit.events.InvalidEventException;
import cl.duoc.pedidos360.audit.events.InventarioEvento;
import cl.duoc.pedidos360.audit.events.PedidoEvento;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor Kafka del consumer group "pedidos360-audit". Lee los DOS tópicos.
 *
 * Consumer group: todas las instancias de ms-audit comparten el grupo y Kafka les reparte
 * las 3 particiones (con 2 instancias: 2 y 1). ms-report usa OTRO grupo, así que recibe
 * los mismos eventos de forma independiente, con sus propios offsets.
 *
 * Cada listener indica el DTO al que se convierte el JSON (spring.json.value.default.type):
 * ErrorHandlingDeserializer -> JacksonJsonDeserializer -> PedidoEvento / InventarioEvento.
 * Si el JSON viene mal formado, el deserializador no lanza la excepción aquí: el
 * DefaultErrorHandler la recibe y manda el mensaje al DLT (ver KafkaErrorConfig).
 */
@Component
@RequiredArgsConstructor
public class AuditListener {

    /** Ojo: sin groupId, Spring usaría el "id" del listener como nombre del grupo. */
    static final String GRUPO = "pedidos360-audit";

    private final AuditService service;

    @KafkaListener(id = "audit-pedidos", groupId = GRUPO, topics = "pedidos.eventos",
            properties = "spring.json.value.default.type=cl.duoc.pedidos360.audit.events.PedidoEvento")
    public void onPedido(ConsumerRecord<String, PedidoEvento> record) {
        if (record.value() == null) {
            throw new InvalidEventException("mensaje vacío en " + record.topic());
        }
        service.registrarPedido(record);
    }

    @KafkaListener(id = "audit-inventario", groupId = GRUPO, topics = "inventario.eventos",
            properties = "spring.json.value.default.type=cl.duoc.pedidos360.audit.events.InventarioEvento")
    public void onInventario(ConsumerRecord<String, InventarioEvento> record) {
        if (record.value() == null) {
            throw new InvalidEventException("mensaje vacío en " + record.topic());
        }
        service.registrarInventario(record);
    }
}
