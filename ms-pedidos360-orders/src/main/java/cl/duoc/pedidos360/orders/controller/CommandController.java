package cl.duoc.pedidos360.orders.controller;

import cl.duoc.pedidos360.orders.messaging.CommandEnvelope;
import cl.duoc.pedidos360.orders.messaging.CommandPublisher;
import cl.duoc.pedidos360.orders.messaging.CommandType;
import cl.duoc.pedidos360.orders.messaging.Exchanges;
import cl.duoc.pedidos360.orders.messaging.payload.EmailCommand;
import cl.duoc.pedidos360.orders.messaging.payload.InvoiceCommand;
import cl.duoc.pedidos360.orders.messaging.payload.KitchenTicketCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Endpoints de prueba para publicar comandos directamente.
 * En la Etapa 4 estos comandos los publicará el flujo real de cambio de estado del pedido.
 *
 * Todos responden 202 Accepted: la petición fue aceptada y el comando quedó en la cola,
 * pero el trabajo (enviar el correo, etc.) todavía no se ha hecho.
 */
@Tag(name = "Comandos (pruebas RabbitMQ)")
@RestController
@RequestMapping("/api/orders/commands")
@RequiredArgsConstructor
public class CommandController {

    private final CommandPublisher publisher;

    @Operation(summary = "Publica un comando de correo en q.cmd.email")
    @PostMapping("/email")
    public ResponseEntity<CommandEnvelope<EmailCommand>> email(
            @Valid @RequestBody EmailCommand body,
            @Parameter(description = "Si se indica (p. ej. high), publica en cmd.topic con routing key email.send.<priority>")
            @RequestParam(required = false) String priority,
            @RequestHeader(value = "X-Event-Id", required = false) UUID eventId) {
        return publish(CommandType.EMAIL_SEND, body, body.pedidoId(), priority, eventId);
    }

    @Operation(summary = "Publica un ticket de preparación en q.cmd.kitchen")
    @PostMapping("/kitchen")
    public ResponseEntity<CommandEnvelope<KitchenTicketCommand>> kitchen(
            @Valid @RequestBody KitchenTicketCommand body,
            @RequestParam(required = false) String priority,
            @RequestHeader(value = "X-Event-Id", required = false) UUID eventId) {
        return publish(CommandType.KITCHEN_TICKET, body, body.pedidoId(), priority, eventId);
    }

    @Operation(summary = "Publica la generación de boleta/factura en q.cmd.invoice")
    @PostMapping("/invoice")
    public ResponseEntity<CommandEnvelope<InvoiceCommand>> invoice(
            @Valid @RequestBody InvoiceCommand body,
            @RequestParam(required = false) String priority,
            @RequestHeader(value = "X-Event-Id", required = false) UUID eventId) {
        return publish(CommandType.INVOICE_GEN, body, body.pedidoId(), priority, eventId);
    }

    /** Sin priority: cmd.direct con la routing key exacta. Con priority: cmd.topic con una variante. */
    private <T> ResponseEntity<CommandEnvelope<T>> publish(CommandType type, T body, Long pedidoId,
                                                           String priority, UUID eventId) {
        boolean variant = priority != null && !priority.isBlank();
        String exchange = variant ? Exchanges.TOPIC : Exchanges.DIRECT;
        String routingKey = variant ? type.routingKey() + "." + priority : type.routingKey();

        CommandEnvelope<T> envelope = publisher.publish(
                exchange, routingKey, type, body, "pedido-" + pedidoId, eventId);
        return ResponseEntity.accepted().body(envelope);
    }
}
