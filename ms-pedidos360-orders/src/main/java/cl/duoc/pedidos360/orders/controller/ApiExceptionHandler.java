package cl.duoc.pedidos360.orders.controller;

import cl.duoc.pedidos360.orders.messaging.PublishException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce errores de publicación a respuestas HTTP claras (formato RFC 9457 Problem Details). */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PublishException.class)
    public ProblemDetail handlePublish(PublishException e) {
        HttpStatus status = e.getMessage().startsWith("Mensaje no enrutado")
                ? HttpStatus.UNPROCESSABLE_CONTENT    // la routing key no tiene cola de destino
                : HttpStatus.SERVICE_UNAVAILABLE;      // broker caído o sin confirmación
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle("Error al publicar en RabbitMQ");
        return problem;
    }
}
