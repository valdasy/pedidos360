package cl.duoc.pedidos360.report.consumer;

import cl.duoc.pedidos360.report.events.InvalidEventException;
import cl.duoc.pedidos360.report.events.PedidoEvento;
import cl.duoc.pedidos360.report.reporte.ReporteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor del consumer group "pedidos360-report".
 * Lee el MISMO tópico que ms-audit, pero con otro grupo: cada grupo tiene sus propios offsets,
 * así que ambos reciben todos los eventos sin quitárselos entre sí (publish/subscribe).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReporteListener {

    static final String GRUPO = "pedidos360-report";

    private final ReporteService service;

    @KafkaListener(id = "report-pedidos", groupId = GRUPO, topics = "pedidos.eventos",
            properties = "spring.json.value.default.type=cl.duoc.pedidos360.report.events.PedidoEvento")
    public void onPedido(ConsumerRecord<String, PedidoEvento> record) {
        if (record.value() == null) {
            throw new InvalidEventException("mensaje vacío en " + record.topic());
        }
        log.debug("Recibido {}-{} offset {} key {}", record.topic(), record.partition(), record.offset(), record.key());
        service.procesar(record.value());
    }
}
