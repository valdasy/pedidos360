package cl.duoc.pedidos360.mqadmin.client;

import cl.duoc.pedidos360.mqadmin.config.MqAdminProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Cliente de la API HTTP de RabbitMQ Management (puerto 15672).
 * Es la misma API que usa la interfaz web: todo lo que se hace con clics se puede
 * hacer desde código.
 *
 * Clúster: cualquier nodo responde por todo el clúster. Se recibe una URL por nodo y, si
 * el nodo actual no responde (caído), se intenta con el siguiente. Así mq-admin sigue
 * funcionando aunque se caiga el nodo 1.
 *
 * Ojo: las estadísticas (contadores, tasas) se refrescan cada ~5 segundos.
 */
@Slf4j
@Component
public class RabbitManagementClient {

    /** Vhost "/" codificado para URL. */
    private static final String VHOST = "%2F";

    private final RestClient http;
    private final List<String> baseUrls;
    /** Índice del último nodo que respondió: se prueba primero. */
    private volatile int preferred = 0;

    public RabbitManagementClient(MqAdminProperties props) {
        this.baseUrls = props.managementUrls();
        var timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));   // un nodo caído no debe trabar la API
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder()
                .requestFactory(timeouts)
                .defaultHeaders(h -> h.setBasicAuth(props.username(), props.password()))
                .build();
    }

    public JsonNode overview() {
        return call(base -> http.get().uri(uri(base, "/overview")).retrieve().body(JsonNode.class));
    }

    public JsonNode nodes() {
        return call(base -> http.get().uri(uri(base, "/nodes")).retrieve().body(JsonNode.class));
    }

    public JsonNode queue(String name) {
        return call(base -> http.get().uri(uri(base, "/queues/" + VHOST + "/" + name)).retrieve().body(JsonNode.class));
    }

    /**
     * Lee mensajes SIN retirarlos (ackmode ack_requeue_true: se entregan y se devuelven).
     * Efecto secundario: quedan marcados como "redelivered".
     */
    public JsonNode peek(String queue, int count) {
        return call(base -> http.post().uri(uri(base, "/queues/" + VHOST + "/" + queue + "/get"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("count", count, "ackmode", "ack_requeue_true", "encoding", "auto", "truncate", 50_000))
                .retrieve().body(JsonNode.class));
    }

    /** Elimina todos los mensajes Ready de la cola. Irreversible. */
    public void purge(String queue) {
        call(base -> http.delete().uri(uri(base, "/queues/" + VHOST + "/" + queue + "/contents"))
                .retrieve().toBodilessEntity());
    }

    /** Ejecuta la petición contra un nodo; si no responde, prueba el siguiente. */
    private <T> T call(Function<String, T> request) {
        ResourceAccessException last = null;
        for (int i = 0; i < baseUrls.size(); i++) {
            int idx = (preferred + i) % baseUrls.size();
            try {
                T result = request.apply(baseUrls.get(idx));
                if (idx != preferred) {
                    log.warn("Management API: {} no responde, usando {}", baseUrls.get(preferred), baseUrls.get(idx));
                    preferred = idx;
                }
                return result;
            } catch (ResourceAccessException e) {   // conexión rechazada o timeout: nodo caído
                last = e;
            }
        }
        throw last;
    }

    // Se construye un URI ya codificado: si se pasara como plantilla, RestClient
    // volvería a codificar el "%" de %2F y la URL quedaría mal (%252F).
    private static URI uri(String base, String path) {
        return URI.create(base + path);
    }
}
