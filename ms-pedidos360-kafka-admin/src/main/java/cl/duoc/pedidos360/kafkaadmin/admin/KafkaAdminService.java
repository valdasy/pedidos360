package cl.duoc.pedidos360.kafkaadmin.admin;

import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.ClusterResponse;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.CrearTopicoRequest;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.GrupoDetalle;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.GrupoResumen;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.Mensaje;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.PublicadoResponse;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.TopicoDetalle;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.TopicoResumen;
import cl.duoc.pedidos360.kafkaadmin.shared.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Administración de Kafka con AdminClient (la "API de administración" del broker).
 * Kafka UI hace lo mismo desde una interfaz web; este microservicio lo expone como API REST
 * protegida con JWT (rol Admin), para automatizarlo o integrarlo al frontend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaAdminService {

    /** Configuraciones que se muestran siempre (aunque tengan el valor por defecto). */
    private static final Set<String> CONFIGS_CLAVE = Set.of("retention.ms", "cleanup.policy",
            "min.insync.replicas", "retention.bytes", "max.message.bytes", "segment.ms");

    private final AdminClient admin;
    private final KafkaTemplate<String, String> kafka;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrap;

    // ---------------------------------------------------------------- clúster

    public ClusterResponse cluster() {
        var c = admin.describeCluster();
        Node controller = get(c.controller());
        List<ClusterResponse.Broker> brokers = get(c.nodes()).stream()
                .sorted(Comparator.comparingInt(Node::id))
                .map(n -> new ClusterResponse.Broker(n.id(), n.host(), n.port(), n.rack())).toList();
        return new ClusterResponse(get(c.clusterId()), controller == null ? null : controller.id(), brokers);
    }

    // ---------------------------------------------------------------- tópicos

    public List<TopicoResumen> listarTopicos(boolean internos) {
        Set<String> nombres = get(admin.listTopics(new ListTopicsOptions().listInternal(internos)).names());
        return describir(nombres).values().stream()
                .sorted(Comparator.comparing(TopicDescription::name))
                .map(d -> new TopicoResumen(d.name(), d.partitions().size(),
                        d.partitions().isEmpty() ? 0 : d.partitions().getFirst().replicas().size(), d.isInternal()))
                .toList();
    }

    public TopicoDetalle describirTopico(String nombre) {
        TopicDescription d = describir(Set.of(nombre)).get(nombre);
        List<TopicPartition> tps = d.partitions().stream().map(p -> new TopicPartition(nombre, p.partition())).toList();
        Map<TopicPartition, Long> inicio = offsets(tps, OffsetSpec.earliest());
        Map<TopicPartition, Long> fin = offsets(tps, OffsetSpec.latest());

        List<TopicoDetalle.Particion> particiones = d.partitions().stream().map(p -> {
            TopicPartition tp = new TopicPartition(nombre, p.partition());
            return new TopicoDetalle.Particion(p.partition(), p.leader() == null ? null : p.leader().id(),
                    ids(p.replicas()), ids(p.isr()), inicio.getOrDefault(tp, 0L), fin.getOrDefault(tp, 0L));
        }).toList();
        long mensajes = particiones.stream().mapToLong(p -> p.offsetFin() - p.offsetInicio()).sum();
        return new TopicoDetalle(nombre, d.topicId().toString(), d.isInternal(), particiones, configs(nombre),
                mensajes);
    }

    public TopicoDetalle crearTopico(CrearTopicoRequest req) {
        int brokers = get(admin.describeCluster().nodes()).size();
        if (req.replicas() > brokers) {
            throw new BadRequestException("Factor de réplica " + req.replicas() + " mayor que los brokers disponibles ("
                    + brokers + ")");
        }
        NewTopic topic = new NewTopic(req.nombre(), req.particiones(), req.replicas());
        if (req.configs() != null && !req.configs().isEmpty()) {
            topic.configs(req.configs());
        }
        get(admin.createTopics(List.of(topic)).all());
        log.info("Tópico creado: {} ({} particiones, réplica {}, configs {})", req.nombre(), req.particiones(),
                req.replicas(), req.configs());
        esperarMetadata(req.nombre(), d -> d.partitions().size() == req.particiones());
        return describirTopico(req.nombre());
    }

    /** SET de cada config; valor null o vacío = DELETE (vuelve al valor por defecto del broker). */
    public TopicoDetalle modificarConfigs(String nombre, Map<String, String> configs) {
        describir(Set.of(nombre));   // 404 si no existe
        ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, nombre);
        List<AlterConfigOp> ops = configs.entrySet().stream().map(e -> e.getValue() == null || e.getValue().isBlank()
                ? new AlterConfigOp(new ConfigEntry(e.getKey(), null), AlterConfigOp.OpType.DELETE)
                : new AlterConfigOp(new ConfigEntry(e.getKey(), e.getValue()), AlterConfigOp.OpType.SET)).toList();
        get(admin.incrementalAlterConfigs(Map.of(recurso, ops)).all());
        log.info("Configuración de {} modificada: {}", nombre, configs);
        return describirTopico(nombre);
    }

    /**
     * Kafka solo permite AUMENTAR particiones. Reducirlas implicaría mover o perder mensajes,
     * por eso no existe esa operación. Ojo: al aumentar, una misma clave puede caer en otra
     * partición que antes (se pierde el orden para eventos nuevos vs antiguos de esa clave).
     */
    public TopicoDetalle aumentarParticiones(String nombre, int total) {
        int actuales = describir(Set.of(nombre)).get(nombre).partitions().size();
        if (total <= actuales) {
            throw new BadRequestException("El tópico ya tiene " + actuales
                    + " particiones. Kafka solo permite aumentarlas (no reducirlas)");
        }
        get(admin.createPartitions(Map.of(nombre, NewPartitions.increaseTo(total))).all());
        log.info("Particiones de {}: {} -> {}", nombre, actuales, total);
        esperarMetadata(nombre, d -> d.partitions().size() >= total);
        return describirTopico(nombre);
    }

    public void eliminarTopico(String nombre) {
        if (nombre.startsWith("__")) {
            throw new BadRequestException("No se pueden eliminar tópicos internos de Kafka");
        }
        describir(Set.of(nombre));
        get(admin.deleteTopics(List.of(nombre)).all());
        log.warn("Tópico eliminado: {}", nombre);
    }

    // ---------------------------------------------------------------- consumer groups

    public List<GrupoResumen> listarGrupos() {
        return get(admin.listGroups().all()).stream()
                .map(g -> new GrupoResumen(g.groupId(),
                        g.groupState().map(Enum::name).orElse("DESCONOCIDO"),
                        g.type().map(Enum::name).orElse("-")))
                .sorted(Comparator.comparing(GrupoResumen::grupoId)).toList();
    }

    /** Miembros, particiones asignadas y LAG (mensajes pendientes) de un consumer group. */
    public GrupoDetalle describirGrupo(String grupoId) {
        ConsumerGroupDescription g = get(admin.describeConsumerGroups(List.of(grupoId)).describedGroups().get(grupoId));
        Map<TopicPartition, OffsetAndMetadata> confirmados =
                get(admin.listConsumerGroupOffsets(grupoId).partitionsToOffsetAndMetadata());
        Map<TopicPartition, Long> fin = offsets(List.copyOf(confirmados.keySet()), OffsetSpec.latest());

        List<GrupoDetalle.OffsetParticion> offsets = confirmados.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .sorted(Comparator.comparing((Map.Entry<TopicPartition, OffsetAndMetadata> e) -> e.getKey().topic())
                        .thenComparingInt(e -> e.getKey().partition()))
                .map(e -> {
                    long finP = fin.getOrDefault(e.getKey(), 0L);
                    long conf = e.getValue().offset();
                    return new GrupoDetalle.OffsetParticion(e.getKey().topic(), e.getKey().partition(), conf, finP,
                            Math.max(0, finP - conf));
                }).toList();

        List<GrupoDetalle.Miembro> miembros = g.members().stream().map(m -> new GrupoDetalle.Miembro(
                m.consumerId(), m.clientId(), m.host(),
                m.assignment().topicPartitions().stream()
                        .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                        .map(tp -> tp.topic() + "-" + tp.partition()).toList())).toList();

        return new GrupoDetalle(g.groupId(), g.groupState().name(),
                g.coordinator() == null ? null : g.coordinator().host() + ":" + g.coordinator().port(),
                g.partitionAssignor(), miembros, offsets,
                offsets.stream().mapToLong(GrupoDetalle.OffsetParticion::lag).sum());
    }

    // ---------------------------------------------------------------- mensajes (pruebas y DLT)

    /**
     * Últimos N mensajes de un tópico (sin unirse a ningún consumer group: no altera offsets).
     * Útil para revisar el DLT: las cabeceras kafka_dlt-* explican por qué falló cada evento.
     */
    public List<Mensaje> ultimosMensajes(String nombre, int limite) {
        TopicDescription d = describir(Set.of(nombre)).get(nombre);
        List<TopicPartition> tps = d.partitions().stream().map(p -> new TopicPartition(nombre, p.partition())).toList();
        Map<TopicPartition, Long> inicio = offsets(tps, OffsetSpec.earliest());
        Map<TopicPartition, Long> fin = offsets(tps, OffsetSpec.latest());
        int porParticion = Math.clamp(limite, 1, 100);

        Map<String, Object> cfg = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.CLIENT_ID_CONFIG, "ms-pedidos360-kafka-admin-peek");
        List<Mensaje> mensajes = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cfg)) {
            consumer.assign(tps);
            Map<TopicPartition, Long> objetivo = new HashMap<>();
            for (TopicPartition tp : tps) {
                long desde = Math.max(inicio.getOrDefault(tp, 0L), fin.getOrDefault(tp, 0L) - porParticion);
                consumer.seek(tp, desde);
                if (fin.getOrDefault(tp, 0L) > desde) {
                    objetivo.put(tp, fin.get(tp));
                }
            }
            long limiteTiempo = System.currentTimeMillis() + 5_000;
            while (!objetivo.isEmpty() && System.currentTimeMillis() < limiteTiempo) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    mensajes.add(new Mensaje(r.partition(), r.offset(), r.key(), r.value(), r.timestamp(),
                            cabeceras(r)));
                    if (r.offset() + 1 >= objetivo.getOrDefault(tp, Long.MAX_VALUE)) {
                        objetivo.remove(tp);
                    }
                }
            }
        }
        return mensajes.stream().sorted(Comparator.comparingLong(Mensaje::timestamp).reversed())
                .limit(porParticion).toList();
    }

    /** Publica un mensaje de texto tal cual (para probar el manejo de errores y el DLT). */
    public PublicadoResponse publicar(String topico, String clave, String valor) {
        describir(Set.of(topico));
        RecordMetadata meta = get(kafka.send(topico, clave, valor)).getRecordMetadata();
        log.warn("Mensaje de prueba publicado en {}-{} offset {}", meta.topic(), meta.partition(), meta.offset());
        return new PublicadoResponse(meta.topic(), meta.partition(), meta.offset());
    }

    // ---------------------------------------------------------------- utilidades

    /**
     * El controlador confirma el cambio antes de que TODOS los brokers actualicen su metadata.
     * Se espera (máx. 5 s) a que el cambio sea visible para responder con datos reales.
     */
    private void esperarMetadata(String nombre, java.util.function.Predicate<TopicDescription> listo) {
        for (int i = 0; i < 20; i++) {
            try {
                if (listo.test(describir(Set.of(nombre)).get(nombre))) {
                    return;
                }
            } catch (org.apache.kafka.common.errors.UnknownTopicOrPartitionException aunNo) {
                // todavía no se propaga
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Map<String, TopicDescription> describir(Set<String> nombres) {
        return get(admin.describeTopics(nombres).allTopicNames());
    }

    private Map<String, String> configs(String nombre) {
        ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, nombre);
        Config config = get(admin.describeConfigs(List.of(recurso)).all()).get(recurso);
        return config.entries().stream()
                .filter(e -> CONFIGS_CLAVE.contains(e.name()) || e.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
                .collect(Collectors.toMap(ConfigEntry::name, e -> String.valueOf(e.value()), (a, b) -> a, TreeMap::new));
    }

    private Map<TopicPartition, Long> offsets(List<TopicPartition> tps, OffsetSpec spec) {
        if (tps.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, OffsetSpec> req = tps.stream().collect(Collectors.toMap(Function.identity(), tp -> spec));
        return get(admin.listOffsets(req).all()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
    }

    private static List<Integer> ids(List<Node> nodes) {
        return nodes.stream().map(Node::id).toList();
    }

    private static Map<String, String> cabeceras(ConsumerRecord<String, String> r) {
        Map<String, String> h = new LinkedHashMap<>();
        r.headers().forEach(x -> {
            // Los kafka_dlt-*-offset/partition/timestamp son binarios; los mensajes de error son texto
            String nombre = x.key();
            if (nombre.endsWith("-offset") || nombre.endsWith("-partition") || nombre.endsWith("-timestamp")
                    || nombre.endsWith("-timestamp-type")) {
                h.put(nombre, "(binario)");
            } else {
                String v = x.value() == null ? null : new String(x.value(), StandardCharsets.UTF_8);
                h.put(nombre, v != null && v.length() > 300 ? v.substring(0, 300) + "…" : v);
            }
        });
        return h;
    }

    /** Espera el resultado de AdminClient y "desenvuelve" la excepción real del broker. */
    private static <T> T get(KafkaFuture<T> future) {
        return get(future.toCompletionStage().toCompletableFuture());
    }

    private static <T> T get(java.util.concurrent.Future<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new KafkaAdminException(e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new KafkaAdminException(new org.apache.kafka.common.errors.TimeoutException(
                    "Kafka no respondió en 20 s"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaAdminException(e);
        }
    }

    /** Envuelve excepciones verificadas para que ApiExceptionHandler las traduzca a HTTP. */
    public static class KafkaAdminException extends RuntimeException {
        public KafkaAdminException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
