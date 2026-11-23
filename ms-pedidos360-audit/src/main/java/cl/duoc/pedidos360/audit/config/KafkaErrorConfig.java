package cl.duoc.pedidos360.audit.config;

import cl.duoc.pedidos360.audit.events.InvalidEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manejo de errores del consumidor Kafka:
 *
 *  1. Error TRANSITORIO (p. ej. Oracle no responde): se reintenta 3 veces con backoff
 *     exponencial (1 s, 2 s, 4 s) sin avanzar el offset de esa partición.
 *  2. Error PERMANENTE (evento inválido o JSON mal formado): no se reintenta, porque
 *     fallaría igual. Va directo al DLT.
 *  3. Agotados los reintentos: el evento se publica en "<tópico>.DLT" (misma partición) con
 *     cabeceras que explican el error, y el consumidor SIGUE con el siguiente evento.
 *     Sin esto, un solo evento malo ("poison pill") bloquearía la partición para siempre.
 *
 * Spring Boot aplica este DefaultErrorHandler a todos los @KafkaListener automáticamente.
 * Equivale a la DLQ de RabbitMQ, pero en Kafka el evento original NO se borra del tópico:
 * el DLT es una copia para revisar y reprocesar.
 */
@Slf4j
@Configuration
public class KafkaErrorConfig {

    /**
     * Productor exclusivo para los DLT. Debe serializar dos cosas distintas:
     *  - byte[]: el mensaje original cuando el JSON no se pudo leer (DeserializationException)
     *  - un DTO: cuando el evento se leyó bien pero falló el procesamiento
     */
    @Bean
    KafkaTemplate<Object, Object> dltTemplate(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        Map<String, Object> cfg = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000,
                ProducerConfig.CLIENT_ID_CONFIG, "ms-pedidos360-audit-dlt");
        JacksonJsonSerializer<Object> json = new JacksonJsonSerializer<>();
        json.setAddTypeInfo(false);
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, json);
        @SuppressWarnings({"unchecked", "rawtypes"})
        DefaultKafkaProducerFactory<Object, Object> pf = new DefaultKafkaProducerFactory<>(cfg,
                (Serializer) new StringSerializer(), (Serializer) new DelegatingByTypeSerializer(byType, true));
        return new KafkaTemplate<>(pf);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> dltTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltTemplate,
                (record, ex) -> {
                    log.error("DLT: {}-{} offset {} -> {}.DLT ({})", record.topic(), record.partition(),
                            record.offset(), record.topic(), rootMessage(ex));
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                });

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(4_000);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Un evento inválido fallará siempre: reintentarlo solo retrasa a los demás
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.setRetryListeners((record, ex, attempt) -> log.warn("Falló el intento {} de {}-{} offset {}: {}",
                attempt, record.topic(), record.partition(), record.offset(), rootMessage(ex)));
        return handler;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
