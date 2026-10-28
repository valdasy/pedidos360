package cl.duoc.pedidos360.notify.service;

import cl.duoc.pedidos360.notify.messaging.InvalidCommandException;
import cl.duoc.pedidos360.notify.messaging.payload.InvoiceCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class InvoiceService {

    /** Simula la generación de la boleta/factura. */
    public void generate(InvoiceCommand cmd) {
        if (cmd.monto() <= 0) {
            throw new InvalidCommandException("Monto inválido para el pedido " + cmd.pedidoId() + ": " + cmd.monto());
        }
        log.info("{} generada: pedido {}, cliente {}, monto ${}",
                cmd.documento() != null ? cmd.documento() : "BOLETA", cmd.pedidoId(), cmd.cliente(), cmd.monto());
    }
}
