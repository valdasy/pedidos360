package cl.duoc.pedidos360.report.reporte;

import cl.duoc.pedidos360.report.reporte.ReporteDtos.KpiResponse;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.TopProducto;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.VentaDiaria;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Solo lectura: los datos se calculan a partir de los eventos de Kafka. */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReporteController {

    private final ReporteService service;

    @Operation(summary = "KPIs: pedidos por estado, ventas, ticket promedio, cancelación y tiempos")
    @GetMapping("/kpis")
    public KpiResponse kpis() {
        return service.kpis();
    }

    @Operation(summary = "Productos más vendidos (pedidos confirmados)")
    @GetMapping("/top-productos")
    public List<TopProducto> topProductos(@RequestParam(defaultValue = "5") int limit) {
        return service.topProductos(limit);
    }

    @Operation(summary = "Ventas confirmadas por día")
    @GetMapping("/ventas-diarias")
    public List<VentaDiaria> ventasDiarias(@RequestParam(defaultValue = "14") int dias) {
        return service.ventasDiarias(dias);
    }
}
