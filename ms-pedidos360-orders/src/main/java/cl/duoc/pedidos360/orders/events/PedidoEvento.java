package cl.duoc.pedidos360.orders.events;

import java.time.Instant;
import java.util.List;

/**
 * DTO tipado del tópico Kafka "pedidos.eventos" (contrato con ms-audit y ms-report).
 *
 * Se serializa a JSON con JacksonJsonSerializer, SIN cabeceras de tipo Java: el consumidor
 * tiene su propia copia del record y no depende del paquete de este microservicio.
 *
 * Diferencia con RabbitMQ: allá se envían COMANDOS ("envía este correo") a UN consumidor.
 * Aquí se publica un HECHO ("el pedido 7 pasó a ACEPTADO") que leen varios consumer groups
 * de forma independiente, y que queda guardado en el log de Kafka (se puede releer).
 *
 * @param eventId       UUID único: los consumidores lo usan para ser idempotentes
 * @param version       versión del contrato, para evolucionar el DTO sin romper consumidores
 * @param estadoAnterior null en PEDIDO_CREADO
 */
public record PedidoEvento(
        String eventId,
        PedidoEventoTipo tipo,
        int version,
        Long pedidoId,
        String estadoAnterior,
        String estado,
        String clienteNombre,
        String clienteEmail,
        long total,
        List<Item> items,
        String usuario,
        Instant ocurridoEn,
        String origen) {

    public static final int VERSION = 1;

    public record Item(Long productoId, String nombre, int cantidad, long precioUnitario) {
    }
}
