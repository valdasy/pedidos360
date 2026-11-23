package cl.duoc.pedidos360.audit.audit;

import cl.duoc.pedidos360.audit.audit.AuditDtos.AuditEventResponse;
import cl.duoc.pedidos360.audit.audit.AuditDtos.ResumenResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Solo lectura: la auditoría se alimenta únicamente desde Kafka. */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService service;

    @Operation(summary = "Buscar eventos (más recientes primero)")
    @GetMapping("/events")
    public List<AuditEventResponse> buscar(@RequestParam(required = false) String entidad,
                                           @RequestParam(required = false) Long entidadId,
                                           @RequestParam(required = false) String tipo,
                                           @RequestParam(required = false) String usuario,
                                           @RequestParam(defaultValue = "100") int limit) {
        return service.buscar(entidad, entidadId, tipo, usuario, limit);
    }

    @Operation(summary = "Timeline completo de un pedido (pedido + movimientos de stock)")
    @GetMapping("/pedidos/{id}/timeline")
    public List<AuditEventResponse> timeline(@PathVariable Long id) {
        return service.timeline(id);
    }

    @Operation(summary = "Cantidad de eventos por tipo")
    @GetMapping("/resumen")
    public ResumenResponse resumen() {
        return service.resumen();
    }
}
