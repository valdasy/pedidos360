package cl.duoc.pedidos360.notify.service;

import cl.duoc.pedidos360.notify.messaging.InvalidCommandException;
import cl.duoc.pedidos360.notify.messaging.payload.EmailCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${pedidos360.mail.from}")
    private String from;

    /** Lanza InvalidCommandException (permanente) o MailException (transitoria, p. ej. SMTP caído). */
    public void send(EmailCommand cmd) {
        if (cmd.email() == null || !cmd.email().contains("@")) {
            throw new InvalidCommandException("Correo de destino inválido: " + cmd.email());
        }

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(cmd.email());
        mail.setSubject(cmd.asunto() != null ? cmd.asunto() : "Pedido " + cmd.pedidoId());
        mail.setText(cmd.mensaje() != null ? cmd.mensaje() : "");
        mailSender.send(mail);

        log.info("Correo enviado a {} (pedido {})", cmd.email(), cmd.pedidoId());
    }
}
