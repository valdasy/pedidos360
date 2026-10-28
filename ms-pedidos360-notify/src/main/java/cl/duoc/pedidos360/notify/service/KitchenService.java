package cl.duoc.pedidos360.notify.service;

import cl.duoc.pedidos360.notify.messaging.InvalidCommandException;
import cl.duoc.pedidos360.notify.messaging.payload.KitchenTicketCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KitchenService {

    /** Simula la impresión del ticket de preparación. */
    public void printTicket(KitchenTicketCommand cmd) {
        if (cmd.productos() == null || cmd.productos().isEmpty()) {
            throw new InvalidCommandException("Ticket sin productos para el pedido " + cmd.pedidoId());
        }

        StringBuilder ticket = new StringBuilder("\n===== TICKET PREPARACIÓN pedido ")
                .append(cmd.pedidoId()).append(" =====\n");
        cmd.productos().forEach(p -> ticket.append(String.format("  %3d x %s%n", p.cantidad(), p.nombre())));
        ticket.append("=========================================");
        log.info(ticket.toString());
    }
}
