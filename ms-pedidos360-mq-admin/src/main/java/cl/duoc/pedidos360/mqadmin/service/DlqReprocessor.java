package cl.duoc.pedidos360.mqadmin.service;

import cl.duoc.pedidos360.mqadmin.config.MqAdminProperties;
import cl.duoc.pedidos360.mqadmin.domain.Dtos.ReprocessResult;
import cl.duoc.pedidos360.mqadmin.domain.Flow;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Devuelve mensajes de una DLQ a su exchange y routing key ORIGINALES
 * (los toma de la cabecera x-death), para que el consumidor los vuelva a intentar.
 *
 * Garantía "sin pérdida": cada mensaje se toma de la DLQ sin confirmar (basicGet
 * con autoAck=false), se publica, se espera la confirmación del broker y recién
 * entonces se hace ACK en la DLQ. Si algo falla en medio, el canal se cierra y
 * RabbitMQ devuelve a la DLQ los mensajes no confirmados.
 * En el peor caso (caída entre publicar y hacer ACK) el mensaje queda duplicado:
 * la idempotencia de ms-notify (eventId) evita repetir el efecto.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqReprocessor {

    /** Cuántas veces se ha reprocesado el mensaje. Evita ciclos infinitos DLQ -> cola -> DLQ. */
    static final String REPROCESS_HEADER = "x-reprocess-count";
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbitTemplate;
    private final MqAdminProperties props;

    public ReprocessResult reprocess(Flow flow, int max) {
        return rabbitTemplate.execute(channel -> {
            channel.confirmSelect();
            int moved = 0;
            List<String> skipped = new ArrayList<>();
            // Los omitidos se mantienen sin confirmar hasta el final; si se
            // devolvieran de inmediato, basicGet volvería a entregar el mismo mensaje.
            List<Long> skippedTags = new ArrayList<>();

            // Solo los mensajes que había al comenzar: si uno vuelve a fallar rápido, regresa
            // a la DLQ durante esta misma llamada y no debe reprocesarse otra vez ahora.
            long pending = Math.min(max, channel.messageCount(flow.dlq()));
            Set<String> seen = new HashSet<>();

            try {
                for (int i = 0; i < pending; i++) {
                    GetResponse msg = channel.basicGet(flow.dlq(), false);
                    if (msg == null) {
                        break;   // DLQ vacía
                    }
                    String id = msg.getProps().getMessageId();
                    if (id != null && !seen.add(id)) {
                        channel.basicNack(msg.getEnvelope().getDeliveryTag(), false, true);
                        break;   // ya volvió a la DLQ: se reintentará en la próxima llamada
                    }
                    String skipReason = skipReason(msg);
                    if (skipReason != null) {
                        skipped.add(id + ": " + skipReason);
                        skippedTags.add(msg.getEnvelope().getDeliveryTag());
                        continue;
                    }
                    republish(channel, msg);
                    channel.basicAck(msg.getEnvelope().getDeliveryTag(), false);
                    moved++;
                }
            } finally {
                for (long tag : skippedTags) {
                    channel.basicNack(tag, false, true);
                }
            }

            log.info("Reproceso {}: {} movidos a la cola original, {} omitidos", flow.dlq(), moved, skipped.size());
            return new ReprocessResult(flow.id(), moved, skipped);
        });
    }

    private void republish(Channel channel, GetResponse msg) throws Exception {
        AMQP.BasicProperties original = msg.getProps();
        Map<String, Object> death = lastDeath(original);
        String exchange = String.valueOf(death.get("exchange"));
        String routingKey = String.valueOf(((List<?>) death.get("routing-keys")).getFirst());

        Map<String, Object> headers = new HashMap<>(original.getHeaders());
        headers.remove("x-death");   // historial de la muerte anterior; RabbitMQ crea uno nuevo si vuelve a fallar
        headers.put(REPROCESS_HEADER, reprocessCount(original) + 1);
        headers.put("x-reprocessed-from", msg.getEnvelope().getRoutingKey());

        AMQP.BasicProperties props = original.builder().headers(headers).deliveryMode(2).build();
        channel.basicPublish(exchange, routingKey, props, msg.getBody());
        channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);

        log.info("Reprocesado messageId={} -> {} [{}] (intento {})",
                original.getMessageId(), exchange, routingKey, reprocessCount(original) + 1);
    }

    /** null si se puede reprocesar; si no, el motivo. */
    private String skipReason(GetResponse msg) {
        if (lastDeath(msg.getProps()) == null) {
            return "sin cabecera x-death (no se conoce su origen)";
        }
        if (reprocessCount(msg.getProps()) >= props.maxReprocess()) {
            return "alcanzó el máximo de " + props.maxReprocess() + " reprocesos: requiere revisión manual";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastDeath(AMQP.BasicProperties props) {
        if (props.getHeaders() == null || !(props.getHeaders().get("x-death") instanceof List<?> deaths)
                || deaths.isEmpty()) {
            return null;
        }
        return (Map<String, Object>) deaths.getFirst();
    }

    private int reprocessCount(AMQP.BasicProperties props) {
        Object value = props.getHeaders() == null ? null : props.getHeaders().get(REPROCESS_HEADER);
        return value instanceof Number n ? n.intValue() : 0;
    }
}
