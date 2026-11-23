package cl.duoc.pedidos360.report.reporte;

import cl.duoc.pedidos360.report.events.PedidoEvento;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.KpiResponse;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.TopProducto;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.VentaDiaria;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReporteService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final RepPedidoRepository pedidos;
    private final EventoProcesadoRepository procesados;

    /**
     * Aplica un evento a la proyección. Todo en UNA transacción: la actualización del pedido y
     * el registro del eventId se guardan juntos o no se guarda nada. Así, si el consumidor se
     * cae a mitad de camino y Kafka reentrega el evento, no se cuenta dos veces.
     *
     * @return false si el evento ya se había aplicado
     */
    @Transactional
    public boolean procesar(PedidoEvento e) {
        e.validar();
        if (procesados.existsById(e.eventId())) {
            log.info("Evento duplicado ignorado: {} ({} pedido {})", e.eventId(), e.tipo(), e.pedidoId());
            return false;
        }
        pedidos.findById(e.pedidoId()).ifPresentOrElse(p -> {
            // La clave del mensaje es el pedidoId: sus eventos llegan en orden por partición.
            // Igual se protege contra un evento más antiguo que el último aplicado.
            if (e.ocurridoEn().isBefore(p.getActualizadoEn())) {
                log.warn("Evento antiguo ignorado para pedido {}: {} ({})", e.pedidoId(), e.estado(), e.ocurridoEn());
            } else {
                p.aplicar(e);
            }
        }, () -> pedidos.save(RepPedido.desde(e)));
        procesados.save(new EventoProcesado(e.eventId(), e.tipo()));
        log.info("Reporte actualizado: pedido {} -> {} ({})", e.pedidoId(), e.estado(), e.tipo());
        return true;
    }

    @Transactional(readOnly = true)
    public KpiResponse kpis() {
        List<RepPedido> todos = pedidos.findAll();
        Map<String, Long> porEstado = new TreeMap<>(todos.stream()
                .collect(Collectors.groupingBy(RepPedido::getEstado, Collectors.counting())));
        List<RepPedido> confirmados = todos.stream().filter(RepPedido::esVentaConfirmada).toList();
        long ventas = confirmados.stream().mapToLong(RepPedido::getTotal).sum();
        long cancelados = porEstado.getOrDefault("CANCELADO", 0L);
        return new KpiResponse(
                todos.size(), porEstado, ventas, confirmados.size(),
                confirmados.isEmpty() ? 0 : ventas / confirmados.size(),
                cancelados,
                todos.isEmpty() ? 0 : Math.round(1000.0 * cancelados / todos.size()) / 10.0,
                promedioMinutos(todos, RepPedido::getAceptadoEn),
                promedioMinutos(todos, RepPedido::getEntregadoEn),
                procesados.count());
    }

    /** Productos más vendidos en pedidos confirmados (unidades y monto). */
    @Transactional(readOnly = true)
    public List<TopProducto> topProductos(int limit) {
        Map<Long, TopProducto> acumulado = new LinkedHashMap<>();
        pedidos.findAll().stream().filter(RepPedido::esVentaConfirmada).flatMap(p -> p.getItems().stream())
                .forEach(i -> acumulado.merge(i.getProductoId(),
                        new TopProducto(i.getProductoId(), i.getNombre(), i.getCantidad(),
                                (long) i.getCantidad() * i.getPrecioUnitario()),
                        (a, b) -> new TopProducto(a.productoId(), a.nombre(), a.unidades() + b.unidades(),
                                a.monto() + b.monto())));
        return acumulado.values().stream()
                .sorted(Comparator.comparingLong(TopProducto::unidades).reversed())
                .limit(Math.clamp(limit, 1, 50)).toList();
    }

    /** Ventas confirmadas por día (según la fecha de creación del pedido, hora de Chile). */
    @Transactional(readOnly = true)
    public List<VentaDiaria> ventasDiarias(int dias) {
        LocalDate desde = LocalDate.now(CHILE).minusDays(Math.clamp(dias, 1, 90) - 1L);
        return pedidos.findAll().stream().filter(RepPedido::esVentaConfirmada)
                .collect(Collectors.groupingBy(p -> LocalDate.ofInstant(p.getCreadoEn(), CHILE), TreeMap::new,
                        Collectors.toList()))
                .entrySet().stream().filter(en -> !en.getKey().isBefore(desde))
                .map(en -> new VentaDiaria(en.getKey(), en.getValue().size(),
                        en.getValue().stream().mapToLong(RepPedido::getTotal).sum()))
                .toList();
    }

    private static Double promedioMinutos(List<RepPedido> todos, Function<RepPedido, Instant> hito) {
        var minutos = todos.stream().filter(p -> hito.apply(p) != null)
                .map(p -> Duration.between(p.getCreadoEn(), hito.apply(p)).toSeconds() / 60.0)
                .filter(Objects::nonNull).toList();
        return minutos.isEmpty() ? null
                : Math.round(10 * minutos.stream().mapToDouble(Double::doubleValue).average().orElse(0)) / 10.0;
    }
}
