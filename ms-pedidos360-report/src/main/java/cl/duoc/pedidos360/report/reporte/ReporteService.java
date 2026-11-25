package cl.duoc.pedidos360.report.reporte;

import cl.duoc.pedidos360.report.events.PedidoEvento;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.Analitica;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.CierreMes;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.Embudo;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.KpiResponse;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.Periodo;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.PorDiaSemana;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.PorHora;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.PuntoProyeccion;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.PuntoSerie;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.TopProducto;
import cl.duoc.pedidos360.report.reporte.ReporteDtos.VentaDiaria;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * Analítica de los últimos {@code dias} días (hoy incluido) y proyección de los
     * {@code diasProyeccion} siguientes. Ver {@link Pronostico} para el modelo.
     */
    @Transactional(readOnly = true)
    public Analitica analitica(int dias, int diasProyeccion) {
        int n = Math.clamp(dias, 7, 180);
        int h = Math.clamp(diasProyeccion, 1, 31);
        LocalDate hoy = LocalDate.now(CHILE);
        LocalDate desde = hoy.minusDays(n - 1L);

        List<RepPedido> todos = pedidos.findAll();
        List<RepPedido> ventas = todos.stream().filter(RepPedido::esVentaConfirmada).toList();
        Map<LocalDate, List<RepPedido>> ventasPorDia = ventas.stream()
                .collect(Collectors.groupingBy(p -> fechaChile(p.getCreadoEn())));

        // Serie con TODOS los días del período (0 si no hubo ventas) + promedio móvil de 7 días
        long[] montos = new long[n];
        List<PuntoSerie> serie = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LocalDate f = desde.plusDays(i);
            List<RepPedido> delDia = ventasPorDia.getOrDefault(f, List.of());
            montos[i] = delDia.stream().mapToLong(RepPedido::getTotal).sum();
            long suma = 0;
            int desdeVentana = Math.max(0, i - 6);
            for (int j = desdeVentana; j <= i; j++) {
                suma += montos[j];
            }
            serie.add(new PuntoSerie(f, delDia.size(), montos[i], Math.round((double) suma / (i - desdeVentana + 1))));
        }

        // El modelo se ajusta sin el día de hoy (está incompleto y bajaría la tendencia)
        Pronostico modelo = Pronostico.ajustar(desde, Arrays.copyOf(montos, n - 1));
        List<PuntoProyeccion> proyeccion = new ArrayList<>(h);
        for (int k = 1; k <= h; k++) {
            LocalDate f = hoy.plusDays(k);
            proyeccion.add(new PuntoProyeccion(f, Math.round(modelo.valor(f)), Math.round(modelo.minimo(f)),
                    Math.round(modelo.maximo(f))));
        }

        // Cierre del mes: lo vendido hasta hoy + lo que el modelo espera para los días que faltan
        LocalDate inicioMes = hoy.withDayOfMonth(1);
        LocalDate finMes = hoy.withDayOfMonth(hoy.lengthOfMonth());
        long ventasMes = sumarVentas(ventasPorDia, inicioMes, hoy);
        long restante = 0;
        for (LocalDate f = hoy.plusDays(1); !f.isAfter(finMes); f = f.plusDays(1)) {
            restante += Math.round(modelo.valor(f));
        }
        CierreMes cierre = new CierreMes(YearMonth.from(hoy).toString(), ventasMes, restante, ventasMes + restante,
                hoy.getDayOfMonth(), hoy.lengthOfMonth());

        List<RepPedido> ventasPeriodo = ventas.stream().filter(p -> enRango(p, desde, hoy)).toList();
        return new Analitica(desde, hoy, serie, proyeccion, Math.round(modelo.pendienteDiaria() * 10) / 10.0,
                resumen(ventasPeriodo, desde, hoy),
                resumen(ventas.stream().filter(p -> enRango(p, desde.minusDays(n), desde.minusDays(1))).toList(),
                        desde.minusDays(n), desde.minusDays(1)),
                cierre, porDiaSemana(ventasPeriodo, desde, n), porHora(ventasPeriodo),
                embudo(todos.stream().filter(p -> enRango(p, desde, hoy)).toList()));
    }

    private static final String[] DIAS = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"};

    private static LocalDate fechaChile(Instant instante) {
        return LocalDate.ofInstant(instante, CHILE);
    }

    private static boolean enRango(RepPedido p, LocalDate desde, LocalDate hasta) {
        LocalDate f = fechaChile(p.getCreadoEn());
        return !f.isBefore(desde) && !f.isAfter(hasta);
    }

    private static long sumarVentas(Map<LocalDate, List<RepPedido>> porDia, LocalDate desde, LocalDate hasta) {
        long total = 0;
        for (LocalDate f = desde; !f.isAfter(hasta); f = f.plusDays(1)) {
            total += porDia.getOrDefault(f, List.of()).stream().mapToLong(RepPedido::getTotal).sum();
        }
        return total;
    }

    private static Periodo resumen(List<RepPedido> ventas, LocalDate desde, LocalDate hasta) {
        long monto = ventas.stream().mapToLong(RepPedido::getTotal).sum();
        long unidades = ventas.stream().flatMap(p -> p.getItems().stream()).mapToLong(RepPedidoItem::getCantidad).sum();
        long clientes = ventas.stream().map(RepPedido::getClienteEmail).filter(Objects::nonNull).distinct().count();
        return new Periodo(desde, hasta, monto, ventas.size(), ventas.isEmpty() ? 0 : monto / ventas.size(),
                clientes, unidades);
    }

    private static List<PorDiaSemana> porDiaSemana(List<RepPedido> ventas, LocalDate desde, int dias) {
        long[] pedidosDia = new long[7];
        long[] montoDia = new long[7];
        int[] veces = new int[7];
        for (int i = 0; i < dias; i++) {
            veces[desde.plusDays(i).getDayOfWeek().getValue() - 1]++;
        }
        for (RepPedido p : ventas) {
            int d = fechaChile(p.getCreadoEn()).getDayOfWeek().getValue() - 1;
            pedidosDia[d]++;
            montoDia[d] += p.getTotal();
        }
        List<PorDiaSemana> r = new ArrayList<>(7);
        for (int d = 0; d < 7; d++) {
            r.add(new PorDiaSemana(d + 1, DIAS[d], pedidosDia[d], montoDia[d],
                    veces[d] == 0 ? 0 : montoDia[d] / veces[d]));
        }
        return r;
    }

    private static List<PorHora> porHora(List<RepPedido> ventas) {
        long[] pedidosHora = new long[24];
        long[] montoHora = new long[24];
        for (RepPedido p : ventas) {
            int hora = p.getCreadoEn().atZone(CHILE).getHour();
            pedidosHora[hora]++;
            montoHora[hora] += p.getTotal();
        }
        List<PorHora> r = new ArrayList<>(24);
        for (int hora = 0; hora < 24; hora++) {
            r.add(new PorHora(hora, pedidosHora[hora], montoHora[hora]));
        }
        return r;
    }

    private static Embudo embudo(List<RepPedido> creados) {
        return new Embudo(creados.size(),
                creados.stream().filter(p -> p.getAceptadoEn() != null).count(),
                creados.stream().filter(p -> p.getEntregadoEn() != null).count(),
                creados.stream().filter(p -> "CANCELADO".equals(p.getEstado())).count());
    }

    private static Double promedioMinutos(List<RepPedido> todos, Function<RepPedido, Instant> hito) {
        var minutos = todos.stream().filter(p -> hito.apply(p) != null)
                .map(p -> Duration.between(p.getCreadoEn(), hito.apply(p)).toSeconds() / 60.0)
                .filter(Objects::nonNull).toList();
        return minutos.isEmpty() ? null
                : Math.round(10 * minutos.stream().mapToDouble(Double::doubleValue).average().orElse(0)) / 10.0;
    }
}
