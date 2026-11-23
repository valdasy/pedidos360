package cl.duoc.pedidos360.kafkaadmin.admin;

import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.AumentarParticionesRequest;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.ClusterResponse;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.CrearTopicoRequest;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.GrupoDetalle;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.GrupoResumen;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.Mensaje;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.ModificarConfigsRequest;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.PublicadoResponse;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.PublicarMensajeRequest;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.TopicoDetalle;
import cl.duoc.pedidos360.kafkaadmin.admin.AdminDtos.TopicoResumen;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/kafka-admin")
@RequiredArgsConstructor
public class KafkaAdminController {

    private final KafkaAdminService service;

    @Operation(summary = "Brokers del clúster y controlador")
    @GetMapping("/cluster")
    public ClusterResponse cluster() {
        return service.cluster();
    }

    @Operation(summary = "Listar tópicos")
    @GetMapping("/topics")
    public List<TopicoResumen> topicos(@RequestParam(defaultValue = "false") boolean internos) {
        return service.listarTopicos(internos);
    }

    @Operation(summary = "Describir un tópico: particiones, líder, réplicas, ISR, offsets y configuración")
    @GetMapping("/topics/{nombre}")
    public TopicoDetalle topico(@PathVariable String nombre) {
        return service.describirTopico(nombre);
    }

    @Operation(summary = "Crear un tópico con particiones, réplicas y configuración")
    @PostMapping("/topics")
    @ResponseStatus(HttpStatus.CREATED)
    public TopicoDetalle crear(@Valid @RequestBody CrearTopicoRequest req) {
        return service.crearTopico(req);
    }

    @Operation(summary = "Modificar configuración (p. ej. retention.ms). Valor vacío = volver al defecto")
    @PatchMapping("/topics/{nombre}/configs")
    public TopicoDetalle configs(@PathVariable String nombre, @Valid @RequestBody ModificarConfigsRequest req) {
        return service.modificarConfigs(nombre, req.configs());
    }

    @Operation(summary = "Aumentar particiones (Kafka no permite reducirlas)")
    @PostMapping("/topics/{nombre}/partitions")
    public TopicoDetalle particiones(@PathVariable String nombre, @Valid @RequestBody AumentarParticionesRequest req) {
        return service.aumentarParticiones(nombre, req.total());
    }

    @Operation(summary = "Eliminar un tópico")
    @DeleteMapping("/topics/{nombre}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable String nombre) {
        service.eliminarTopico(nombre);
    }

    @Operation(summary = "Últimos mensajes de un tópico (útil para revisar el DLT)")
    @GetMapping("/topics/{nombre}/messages")
    public List<Mensaje> mensajes(@PathVariable String nombre, @RequestParam(defaultValue = "10") int limit) {
        return service.ultimosMensajes(nombre, limit);
    }

    @Operation(summary = "Publicar un mensaje de prueba tal cual (para demostrar errores y DLT)")
    @PostMapping("/topics/{nombre}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public PublicadoResponse publicar(@PathVariable String nombre, @Valid @RequestBody PublicarMensajeRequest req) {
        return service.publicar(nombre, req.clave(), req.valor());
    }

    @Operation(summary = "Listar consumer groups")
    @GetMapping("/groups")
    public List<GrupoResumen> grupos() {
        return service.listarGrupos();
    }

    @Operation(summary = "Detalle de un consumer group: miembros, particiones asignadas, offsets y LAG")
    @GetMapping("/groups/{grupoId}")
    public GrupoDetalle grupo(@PathVariable String grupoId) {
        return service.describirGrupo(grupoId);
    }
}
