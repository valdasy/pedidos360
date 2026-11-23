package cl.duoc.pedidos360.kafkaadmin.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

public final class AdminDtos {

    private AdminDtos() {
    }

    // ---------- Peticiones ----------

    /** Nombre válido en Kafka: letras, números, punto, guion y guion bajo (máx. 249). */
    public record CrearTopicoRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,249}", message = "solo letras, números, '.', '_' y '-'")
            String nombre,
            @Min(1) @Max(50) int particiones,
            @Min(1) @Max(3) short replicas,
            Map<String, String> configs) {
    }

    /** Valor null = volver al valor por defecto del broker (DELETE de la config). */
    public record ModificarConfigsRequest(@NotEmpty Map<String, String> configs) {
    }

    public record AumentarParticionesRequest(@Min(1) @Max(50) int total) {
    }

    public record PublicarMensajeRequest(String clave, @NotBlank String valor) {
    }

    // ---------- Respuestas ----------

    public record ClusterResponse(String clusterId, Integer controllerId, List<Broker> brokers) {
        public record Broker(int id, String host, int port, String rack) {
        }
    }

    public record TopicoResumen(String nombre, int particiones, int replicas, boolean interno) {
    }

    public record TopicoDetalle(String nombre, String topicId, boolean interno, List<Particion> particiones,
                                Map<String, String> configs, long mensajes) {
        public record Particion(int id, Integer lider, List<Integer> replicas, List<Integer> isr,
                                long offsetInicio, long offsetFin) {
        }
    }

    public record GrupoResumen(String grupoId, String estado, String tipo) {
    }

    public record GrupoDetalle(String grupoId, String estado, String coordinador, String asignador,
                               List<Miembro> miembros, List<OffsetParticion> offsets, long lagTotal) {
        public record Miembro(String consumerId, String clientId, String host, List<String> particiones) {
        }

        public record OffsetParticion(String topico, int particion, long offsetConfirmado, long offsetFin,
                                      long lag) {
        }
    }

    public record Mensaje(int particion, long offset, String clave, String valor, long timestamp,
                          Map<String, String> cabeceras) {
    }

    public record PublicadoResponse(String topico, int particion, long offset) {
    }
}
