package cl.duoc.pedidos360.report.reporte;

import java.time.LocalDate;
import java.util.Map;

public final class ReporteDtos {

    private ReporteDtos() {
    }

    /**
     * @param ventasConfirmadas       suma de pedidos aceptados en adelante (no cancelados)
     * @param tiempoEntregaPromedioMin desde creado hasta entregado (lead time)
     */
    public record KpiResponse(
            long totalPedidos,
            Map<String, Long> pedidosPorEstado,
            long ventasConfirmadas,
            long pedidosConfirmados,
            long ticketPromedio,
            long cancelados,
            double tasaCancelacion,
            Double tiempoAceptacionPromedioMin,
            Double tiempoEntregaPromedioMin,
            long eventosProcesados) {
    }

    public record TopProducto(Long productoId, String nombre, long unidades, long monto) {
    }

    public record VentaDiaria(LocalDate fecha, long pedidos, long monto) {
    }
}
