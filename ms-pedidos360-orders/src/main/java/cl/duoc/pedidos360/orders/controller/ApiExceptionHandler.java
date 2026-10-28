package cl.duoc.pedidos360.orders.controller;

import cl.duoc.pedidos360.orders.catalog.CatalogClient.CatalogUnavailableException;
import cl.duoc.pedidos360.orders.messaging.PublishException;
import cl.duoc.pedidos360.orders.shared.ConflictException;
import cl.duoc.pedidos360.orders.shared.ForbiddenException;
import cl.duoc.pedidos360.orders.shared.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce errores a respuestas HTTP claras (formato RFC 9457 Problem Details). */
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

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail forbidden(ForbiddenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Regla de negocio: transición inválida, stock insuficiente, edición fuera de CREADO. */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        e.getDetails().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail concurrentEdit(ObjectOptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "El pedido fue modificado al mismo tiempo por otra persona; vuelve a cargarlo");
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    public ProblemDetail catalogDown(CatalogUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Servicio de catálogo no disponible");
        return problem;
    }
}
