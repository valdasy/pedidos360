package cl.duoc.pedidos360.notify.service;

import cl.duoc.pedidos360.notify.messaging.InvalidCommandException;
import cl.duoc.pedidos360.notify.messaging.payload.CreditNoteCommand;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Genera la nota de crédito de un pedido cancelado y la envía por correo al cliente.
 *
 * El número es determinístico (NC-000123 para el pedido 123): si el mensaje llegara dos
 * veces, el documento sería el mismo, no uno nuevo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditNoteService {

    private static final NumberFormat CLP = NumberFormat.getCurrencyInstance(Locale.of("es", "CL"));
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private final JavaMailSender mailSender;

    @Value("${pedidos360.mail.from}")
    private String from;

    public static String numero(Long pedidoId) {
        return "NC-%06d".formatted(pedidoId);
    }

    /** Lanza InvalidCommandException (permanente) o MailException (transitoria, p. ej. SMTP caído). */
    public void issue(CreditNoteCommand cmd) {
        if (cmd.email() == null || !cmd.email().contains("@")) {
            throw new InvalidCommandException("Nota de crédito sin correo válido: " + cmd.email());
        }
        if (cmd.monto() <= 0) {
            throw new InvalidCommandException("Monto inválido para la nota de crédito del pedido " + cmd.pedidoId());
        }

        String numero = numero(cmd.pedidoId());
        try {
            MimeMessage mail = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mail, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(cmd.email());
            helper.setSubject("Nota de crédito " + numero + " - Pedido #" + cmd.pedidoId());
            helper.setText(html(cmd, numero), true);
            mailSender.send(mail);
        } catch (MessagingException e) {
            throw new MailPreparationException("No se pudo armar el correo de la nota de crédito", e);
        }
        log.info("Nota de crédito {} emitida: pedido {}, cliente {}, monto {}",
                numero, cmd.pedidoId(), cmd.cliente(), CLP.format(cmd.monto()));
    }

    private String html(CreditNoteCommand cmd, String numero) {
        StringBuilder filas = new StringBuilder();
        if (cmd.items() != null) {
            for (CreditNoteCommand.Item i : cmd.items()) {
                filas.append("<tr><td>").append(esc(i.nombre())).append("</td>")
                        .append("<td style='text-align:right'>").append(i.cantidad()).append("</td>")
                        .append("<td style='text-align:right'>").append(CLP.format(i.precioUnitario())).append("</td>")
                        .append("<td style='text-align:right'>").append(CLP.format(i.precioUnitario() * i.cantidad()))
                        .append("</td></tr>");
            }
        }
        String fecha = ZonedDateTime.now(ZoneId.of("America/Santiago")).format(FECHA);

        return """
                <div style="font-family:Segoe UI,Arial,sans-serif;max-width:600px;color:#1d2433">
                  <h2 style="margin:0">Pedidos360</h2>
                  <p style="color:#6b7486;margin-top:4px">Nota de crédito electrónica</p>
                  <table style="width:100%%;border:1px solid #e2e6ee;border-radius:6px;padding:8px;margin-bottom:16px">
                    <tr><td><b>N°</b></td><td>%s</td></tr>
                    <tr><td><b>Fecha</b></td><td>%s</td></tr>
                    <tr><td><b>Cliente</b></td><td>%s (%s)</td></tr>
                    <tr><td><b>Referencia</b></td><td>Pedido #%d</td></tr>
                    <tr><td><b>Motivo</b></td><td>%s</td></tr>
                  </table>
                  <table style="width:100%%;border-collapse:collapse" cellpadding="6">
                    <tr style="background:#f4f6f9"><th align="left">Producto</th><th align="right">Cant.</th>
                        <th align="right">Precio</th><th align="right">Subtotal</th></tr>
                    %s
                    <tr style="border-top:2px solid #1d2433"><td colspan="3"><b>Total a reembolsar</b></td>
                        <td style="text-align:right"><b>%s</b></td></tr>
                  </table>
                  <p style="color:#6b7486;font-size:12px;margin-top:24px">
                    Documento de prueba generado por el proyecto académico Pedidos360. Sin validez tributaria.
                  </p>
                </div>
                """.formatted(numero, fecha, esc(cmd.cliente()), esc(cmd.email()), cmd.pedidoId(),
                esc(cmd.motivo()), filas, CLP.format(cmd.monto()));
    }

    /** Escapa texto para HTML (evita que un nombre con "<" rompa o inyecte el correo). */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
