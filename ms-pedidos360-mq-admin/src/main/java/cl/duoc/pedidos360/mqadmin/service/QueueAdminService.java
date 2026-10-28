package cl.duoc.pedidos360.mqadmin.service;

import cl.duoc.pedidos360.mqadmin.client.RabbitManagementClient;
import cl.duoc.pedidos360.mqadmin.config.MqAdminProperties;
import cl.duoc.pedidos360.mqadmin.domain.Dtos.*;
import cl.duoc.pedidos360.mqadmin.domain.Flow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueAdminService {

    private final RabbitManagementClient management;
    private final MqAdminProperties props;

    public Overview overview() {
        JsonNode o = management.overview();
        List<NodeStatus> nodes = new ArrayList<>();
        for (JsonNode n : management.nodes()) {
            nodes.add(new NodeStatus(
                    n.path("name").asString(),
                    n.path("running").asBoolean(),
                    n.path("mem_used").asLong() / (1024 * 1024),
                    n.path("disk_free").asLong() / (1024 * 1024)));
        }
        return new Overview(
                o.path("rabbitmq_version").asString(),
                o.path("cluster_name").asString(),
                o.path("queue_totals").path("messages").asLong(),
                o.path("object_totals").path("consumers").asInt(),
                nodes);
    }

    public List<FlowStatus> flows() {
        return Arrays.stream(Flow.values()).map(this::flow).toList();
    }

    public FlowStatus flow(Flow flow) {
        return new FlowStatus(flow.id(), queueStatus(flow.queue()), queueStatus(flow.dlq()));
    }

    public List<DlqMessage> peekDlq(Flow flow, int count) {
        List<DlqMessage> result = new ArrayList<>();
        for (JsonNode m : management.peek(flow.dlq(), count)) {
            JsonNode p = m.path("properties");
            JsonNode headers = p.path("headers");
            // x-death es una lista; el primer elemento es la muerte más reciente
            JsonNode death = headers.path("x-death").path(0);
            result.add(new DlqMessage(
                    p.path("message_id").asString(null),
                    p.path("type").asString(null),
                    death.path("reason").asString(null),
                    death.path("exchange").asString(null),
                    death.path("routing-keys").path(0).asString(null),
                    death.path("count").asLong(),
                    headers.path(DlqReprocessor.REPROCESS_HEADER).asInt(0),
                    m.path("payload").asString()));
        }
        return result;
    }

    public void purgeDlq(Flow flow) {
        long before = queueStatus(flow.dlq()).ready();
        management.purge(flow.dlq());
        log.warn("DLQ {} purgada (≈{} mensajes eliminados)", flow.dlq(), before);
    }

    /**
     * Reglas de alerta:
     *  - cola principal sin consumidores        -> CRITICAL (nadie procesa)
     *  - cola principal con mucha acumulación   -> WARNING  (consumidores lentos)
     *  - DLQ con mensajes                       -> WARNING / CRITICAL según umbral
     */
    public List<Alert> alerts() {
        var cfg = props.alerts();
        List<Alert> alerts = new ArrayList<>();
        for (FlowStatus f : flows()) {
            QueueStatus main = f.main();
            QueueStatus dlq = f.dlq();
            if (main.consumers() == 0) {
                alerts.add(new Alert(Level.CRITICAL, main.name(),
                        "Sin consumidores: los mensajes se acumulan sin procesarse (" + main.ready() + " en espera)"));
            }
            if (main.ready() >= cfg.backlogWarning()) {
                alerts.add(new Alert(Level.WARNING, main.name(),
                        "Acumulación de " + main.ready() + " mensajes: considerar más consumidores"));
            }
            if (dlq.ready() > 0) {
                Level level = dlq.ready() >= cfg.dlqCritical() ? Level.CRITICAL : Level.WARNING;
                alerts.add(new Alert(level, dlq.name(),
                        dlq.ready() + " mensajes fallidos esperan revisión o reproceso"));
            }
        }
        return alerts;
    }

    /** Revisión periódica: deja las alertas en el log (en producción iría a un sistema de monitoreo). */
    @Scheduled(fixedDelayString = "${pedidos360.mq.alerts.check-interval-ms}", initialDelay = 10_000)
    public void logAlerts() {
        try {
            alerts().forEach(a -> log.warn("[ALERTA {}] {}: {}", a.level(), a.queue(), a.message()));
        } catch (RuntimeException e) {
            log.error("No se pudo consultar RabbitMQ Management: {}", e.getMessage());
        }
    }

    private QueueStatus queueStatus(String name) {
        JsonNode q = management.queue(name);
        return new QueueStatus(
                name,
                q.path("messages_ready").asLong(),
                q.path("messages_unacknowledged").asLong(),
                q.path("consumers").asInt(),
                q.path("message_stats").path("publish_details").path("rate").asDouble(),
                q.path("message_stats").path("deliver_get_details").path("rate").asDouble());
    }
}
