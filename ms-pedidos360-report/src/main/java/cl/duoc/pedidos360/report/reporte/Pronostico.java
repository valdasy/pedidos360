package cl.duoc.pedidos360.report.reporte;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * Pronóstico simple de ventas diarias: TENDENCIA lineal × ESTACIONALIDAD semanal.
 *
 * 1. Tendencia: recta de mínimos cuadrados sobre las ventas de cada día (y = a + b·x).
 * 2. Estacionalidad: cuánto vende cada día de la semana respecto del promedio
 *    (p. ej. sábado 1,3 = vende 30 % más). Solo se usa con 2 semanas de datos o más.
 * 3. Banda: ± una desviación estándar del error del modelo sobre los días conocidos.
 *
 * No es un modelo de machine learning: es lo que haría un analista en una planilla, y se
 * puede explicar en una frase. Se ajusta solo con días COMPLETOS (hoy todavía no termina).
 */
final class Pronostico {

    private final LocalDate inicio;
    private final double a;
    private final double b;
    private final double[] factorDia = new double[7];
    private final double desviacion;

    private Pronostico(LocalDate inicio, double a, double b, double[] factores, double desviacion) {
        this.inicio = inicio;
        this.a = a;
        this.b = b;
        System.arraycopy(factores, 0, factorDia, 0, 7);
        this.desviacion = desviacion;
    }

    /** @param valores ventas del día {@code inicio + i} */
    static Pronostico ajustar(LocalDate inicio, long[] valores) {
        int m = valores.length;
        double[] unos = new double[7];
        Arrays.fill(unos, 1.0);
        if (m == 0) {
            return new Pronostico(inicio, 0, 0, unos, 0);
        }
        double mediaX = (m - 1) / 2.0;
        double mediaY = Arrays.stream(valores).average().orElse(0);
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < m; i++) {
            sxy += (i - mediaX) * (valores[i] - mediaY);
            sxx += (i - mediaX) * (i - mediaX);
        }
        double b = sxx == 0 ? 0 : sxy / sxx;
        double a = mediaY - b * mediaX;

        double[] factores = unos.clone();
        if (m >= 14 && mediaY > 0) {
            // Factor = promedio de (real / tendencia) para cada día de la semana
            double[] suma = new double[7];
            int[] cuenta = new int[7];
            for (int i = 0; i < m; i++) {
                double tendencia = a + b * i;
                if (tendencia > 0) {
                    int d = inicio.plusDays(i).getDayOfWeek().getValue() - 1;
                    suma[d] += valores[i] / tendencia;
                    cuenta[d]++;
                }
            }
            for (int d = 0; d < 7; d++) {
                factores[d] = cuenta[d] == 0 ? 1.0 : suma[d] / cuenta[d];
            }
        }

        Pronostico sinBanda = new Pronostico(inicio, a, b, factores, 0);
        double sumaErrores = 0;
        for (int i = 0; i < m; i++) {
            double error = valores[i] - sinBanda.valor(inicio.plusDays(i));
            sumaErrores += error * error;
        }
        return new Pronostico(inicio, a, b, factores, Math.sqrt(sumaErrores / m));
    }

    /** Venta esperada para una fecha (nunca negativa). */
    double valor(LocalDate fecha) {
        long x = ChronoUnit.DAYS.between(inicio, fecha);
        double v = (a + b * x) * factorDia[fecha.getDayOfWeek().getValue() - 1];
        return Math.max(0, v);
    }

    double minimo(LocalDate fecha) {
        return Math.max(0, valor(fecha) - desviacion);
    }

    double maximo(LocalDate fecha) {
        return valor(fecha) + desviacion;
    }

    /** Pendiente de la tendencia: cuántos pesos sube (o baja) la venta diaria por cada día. */
    double pendienteDiaria() {
        return b;
    }
}
