package cl.duoc.pedidos360.report.reporte;

import java.time.LocalDate;
import java.util.List;
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

    /**
     * Analítica de un período: serie diaria, proyección, comparación con el período anterior,
     * cierre estimado del mes y distribución por día de la semana y por hora.
     * Solo cuenta ventas confirmadas (aceptado en adelante, no cancelado), salvo el embudo.
     */
    public record Analitica(
            LocalDate desde,
            LocalDate hasta,
            List<PuntoSerie> serie,
            List<PuntoProyeccion> proyeccion,
            double pendienteDiaria,
            Periodo periodo,
            Periodo periodoAnterior,
            CierreMes cierreMes,
            List<PorDiaSemana> porDiaSemana,
            List<PorHora> porHora,
            Embudo embudo) {
    }

    /** @param promedioMovil7 promedio de las ventas de los últimos 7 días (suaviza la serie) */
    public record PuntoSerie(LocalDate fecha, long pedidos, long monto, long promedioMovil7) {
    }

    /** Venta esperada y su banda (± 1 desviación estándar del error del modelo). */
    public record PuntoProyeccion(LocalDate fecha, long monto, long minimo, long maximo) {
    }

    public record Periodo(LocalDate desde, LocalDate hasta, long ventas, long pedidos, long ticketPromedio,
                          long clientes, long unidades) {
    }

    /** @param estimadoCierre ventas del mes a la fecha + proyección de los días que faltan */
    public record CierreMes(String mes, long ventasALaFecha, long proyectadoRestante, long estimadoCierre,
                            int diasTranscurridos, int diasDelMes) {
    }

    /** @param dia 1 = lunes … 7 = domingo; promedioDiario = monto / cantidad de ese día en el período */
    public record PorDiaSemana(int dia, String nombre, long pedidos, long monto, long promedioDiario) {
    }

    public record PorHora(int hora, long pedidos, long monto) {
    }

    /** Pedidos CREADOS en el período y cuántos llegaron a cada etapa. */
    public record Embudo(long creados, long aceptados, long entregados, long cancelados) {
    }
}
