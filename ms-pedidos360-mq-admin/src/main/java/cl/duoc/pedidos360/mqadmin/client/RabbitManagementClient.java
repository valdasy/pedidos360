package cl.duoc.pedidos360.mqadmin.client;

import cl.duoc.pedidos360.mqadmin.config.MqAdminProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Map;

/**
 * Cliente de la API HTTP de RabbitMQ Management (puerto 15672).
 * Es la misma API que usa la interfaz web: todo lo que se hace con clics se puede
 * hacer desde código.
 *
 * Ojo: las estadísticas (contadores, tasas) se refrescan cada ~5 segundos.
 */
@Component
public class RabbitManagementClient {

    /** Vhost "/" codificado para URL. */
    private static final String VHOST = "%2F";

    private final RestClient http;
    private final String baseUrl;

    public RabbitManagementClient(MqAdminProperties props) {
        this.baseUrl = props.managementUrl();
        this.http = RestClient.builder()
                .defaultHeaders(h -> h.setBasicAuth(props.username(), props.password()))
                .build();
    }

    public JsonNode overview() {
        return http.get().uri(uri("/overview")).retrieve().body(JsonNode.class);
    }

    public JsonNode nodes() {
        return http.get().uri(uri("/nodes")).retrieve().body(JsonNode.class);
    }

    public JsonNode queue(String name) {
        return http.get().uri(uri("/queues/" + VHOST + "/" + name)).retrieve().body(JsonNode.class);
    }

    /**
     * Lee mensajes SIN retirarlos (ackmode ack_requeue_true: se entregan y se devuelven).
     * Efecto secundario: quedan marcados como "redelivered".
     */
    public JsonNode peek(String queue, int count) {
        return http.post().uri(uri("/queues/" + VHOST + "/" + queue + "/get"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("count", count, "ackmode", "ack_requeue_true", "encoding", "auto", "truncate", 50_000))
                .retrieve().body(JsonNode.class);
    }

    /** Elimina todos los mensajes Ready de la cola. Irreversible. */
    public void purge(String queue) {
        http.delete().uri(uri("/queues/" + VHOST + "/" + queue + "/contents")).retrieve().toBodilessEntity();
    }

    // Se construye un URI ya codificado: si se pasara como plantilla, RestClient
    // volvería a codificar el "%" de %2F y la URL quedaría mal (%252F).
    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }
}
