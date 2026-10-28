package cl.duoc.pedidos360.orders.catalog;

import cl.duoc.pedidos360.orders.security.CurrentUser;
import cl.duoc.pedidos360.orders.shared.ConflictException;
import cl.duoc.pedidos360.orders.shared.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Llamadas SÍNCRONAS a ms-catalog.
 *
 * ¿Por qué aquí no se usa RabbitMQ? Porque orders necesita la respuesta en el
 * momento: no puede aceptar un pedido sin saber si hay stock. Las colas sirven
 * para trabajo que puede hacerse después (correos, tickets, boletas).
 */
@Component
public class CatalogClient {

    public record Product(Long id, String nombre, long precio, int stock) {
    }

    public record ReservationItem(Long productoId, int cantidad) {
    }

    private final RestClient http;

    public CatalogClient(@Value("${pedidos360.catalog.url}") String baseUrl, CurrentUser currentUser) {
        var timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(3));
        timeouts.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeouts)
                // Token relay: reenvía el JWT del usuario. ms-catalog aplica sus propias
                // reglas de rol (p. ej. solo Operador/Admin pueden descontar stock).
                .requestInterceptor((request, body, execution) -> {
                    currentUser.bearerToken().ifPresent(t -> request.getHeaders().setBearerAuth(t));
                    return execution.execute(request, body);
                })
                .build();
    }

    public Product getProduct(Long id) {
        return call(() -> http.get().uri("/api/catalog/products/{id}", id).retrieve().body(Product.class));
    }

    /** Descuenta stock del pedido (todo o nada). Lanza ConflictException si falta stock. */
    public void reserve(Long pedidoId, List<ReservationItem> items) {
        call(() -> http.post().uri("/api/catalog/stock/reservations")
                .body(Map.of("pedidoId", pedidoId, "items", items))
                .retrieve().toBodilessEntity());
    }

    /** Devuelve el stock de un pedido cancelado. */
    public void release(Long pedidoId) {
        call(() -> http.delete().uri("/api/catalog/stock/reservations/{id}", pedidoId)
                .retrieve().toBodilessEntity());
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new NotFoundException("ms-catalog: " + detail(e));
            }
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = e.getResponseBodyAs(Map.class);
                throw new ConflictException("ms-catalog: " + detail(e),
                        body != null && body.containsKey("items") ? Map.of("items", body.get("items")) : Map.of());
            }
            throw e;
        } catch (ResourceAccessException e) {
            throw new CatalogUnavailableException(e);
        }
    }

    private String detail(HttpClientErrorException e) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = e.getResponseBodyAs(Map.class);
        return body != null && body.get("detail") != null ? body.get("detail").toString() : e.getStatusText();
    }

    public static class CatalogUnavailableException extends RuntimeException {
        CatalogUnavailableException(Throwable cause) {
            super("ms-catalog no responde", cause);
        }
    }
}
