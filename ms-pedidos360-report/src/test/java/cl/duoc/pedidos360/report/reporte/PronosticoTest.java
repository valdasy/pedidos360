package cl.duoc.pedidos360.report.reporte;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PronosticoTest {

    private static final LocalDate LUNES = LocalDate.of(2026, 11, 2);

    @Test
    void tendenciaLinealSinRuido() {
        long[] v = new long[21];
        for (int i = 0; i < v.length; i++) {
            v[i] = 1000 + 100L * i;
        }
        Pronostico p = Pronostico.ajustar(LUNES, v);
        assertThat(p.pendienteDiaria()).isCloseTo(100, within(0.001));
        assertThat(p.valor(LUNES.plusDays(21))).isCloseTo(3100, within(0.5));
        // sin error, la banda es cero
        assertThat(p.maximo(LUNES.plusDays(21)) - p.minimo(LUNES.plusDays(21))).isCloseTo(0, within(0.5));
    }

    @Test
    void estacionalidadSemanal() {
        // sábado vende el doble que el resto de los días; sin tendencia
        long[] v = new long[28];
        for (int i = 0; i < v.length; i++) {
            v[i] = LUNES.plusDays(i).getDayOfWeek() == DayOfWeek.SATURDAY ? 2000 : 1000;
        }
        Pronostico p = Pronostico.ajustar(LUNES, v);
        LocalDate proximoSabado = LUNES.plusDays(33);
        LocalDate proximoMartes = LUNES.plusDays(29);
        assertThat(proximoSabado.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(p.valor(proximoSabado) / p.valor(proximoMartes)).isCloseTo(2.0, within(0.05));
    }

    @Test
    void nuncaNegativoNiFallaSinDatos() {
        Pronostico vacio = Pronostico.ajustar(LUNES, new long[0]);
        assertThat(vacio.valor(LUNES.plusDays(3))).isZero();

        long[] cayendo = {5000, 4000, 3000, 2000, 1000, 0, 0};
        Pronostico p = Pronostico.ajustar(LUNES, cayendo);
        assertThat(p.valor(LUNES.plusDays(20))).isZero();
        assertThat(p.minimo(LUNES.plusDays(20))).isZero();
    }
}
