package cl.duoc.pedidos360.mqadmin.controller;

import cl.duoc.pedidos360.mqadmin.domain.Dtos.*;
import cl.duoc.pedidos360.mqadmin.domain.Flow;
import cl.duoc.pedidos360.mqadmin.service.DlqReprocessor;
import cl.duoc.pedidos360.mqadmin.service.QueueAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Administración RabbitMQ")
@RestController
@RequestMapping("/api/mq-admin")
@RequiredArgsConstructor
public class MqAdminController {

    private final QueueAdminService admin;
    private final DlqReprocessor reprocessor;

    @Operation(summary = "Estado del broker: versión, total de mensajes y nodos del clúster")
    @GetMapping("/overview")
    public Overview overview() {
        return admin.overview();
    }

    @Operation(summary = "Contadores de las 3 colas principales y sus DLQ")
    @GetMapping("/flows")
    public List<FlowStatus> flows() {
        return admin.flows();
    }

    @Operation(summary = "Contadores de un flujo (email, kitchen, invoice)")
    @GetMapping("/flows/{flow}")
    public FlowStatus flow(@PathVariable String flow) {
        return admin.flow(Flow.from(flow));
    }

    @Operation(summary = "Ver mensajes de la DLQ sin retirarlos, con el motivo del fallo (x-death)")
    @GetMapping("/flows/{flow}/dlq/messages")
    public List<DlqMessage> peekDlq(@PathVariable String flow,
                                    @RequestParam(defaultValue = "10") @Min(1) @Max(100) int count) {
        return admin.peekDlq(Flow.from(flow), count);
    }

    @Operation(summary = "Reprocesar: devuelve mensajes de la DLQ a su exchange y routing key originales")
    @PostMapping("/flows/{flow}/dlq/reprocess")
    public ReprocessResult reprocess(@PathVariable String flow,
                                     @RequestParam(defaultValue = "10") @Min(1) @Max(1000) int count) {
        return reprocessor.reprocess(Flow.from(flow), count);
    }

    @Operation(summary = "Purgar la DLQ (elimina definitivamente sus mensajes)")
    @DeleteMapping("/flows/{flow}/dlq/messages")
    public ResponseEntity<Void> purgeDlq(@PathVariable String flow) {
        admin.purgeDlq(Flow.from(flow));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Alertas activas: colas sin consumidores, acumulación y mensajes en DLQ")
    @GetMapping("/alerts")
    public List<Alert> alerts() {
        return admin.alerts();
    }
}
