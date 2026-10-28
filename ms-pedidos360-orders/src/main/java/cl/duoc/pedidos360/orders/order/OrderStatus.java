package cl.duoc.pedidos360.orders.order;

import java.util.Set;

/**
 * Máquina de estados del pedido:
 *
 *   CREADO -> ACEPTADO -> EN_PREPARACION -> DESPACHADO -> ENTREGADO
 *      \          \              \
 *       +----------+--------------+--> CANCELADO
 *
 * La regla del caso "no se puede despachar sin aceptar" se cumple porque a
 * DESPACHADO solo se llega desde EN_PREPARACION, y a esta solo desde ACEPTADO.
 */
public enum OrderStatus {

    CREADO, ACEPTADO, EN_PREPARACION, DESPACHADO, ENTREGADO, CANCELADO;

    public Set<OrderStatus> next() {
        return switch (this) {
            case CREADO -> Set.of(ACEPTADO, CANCELADO);
            case ACEPTADO -> Set.of(EN_PREPARACION, CANCELADO);
            case EN_PREPARACION -> Set.of(DESPACHADO, CANCELADO);
            case DESPACHADO -> Set.of(ENTREGADO);
            case ENTREGADO, CANCELADO -> Set.of();   // estados finales
        };
    }

    public boolean canMoveTo(OrderStatus target) {
        return next().contains(target);
    }

    /** Estados en los que el stock ya fue descontado (al cancelar hay que devolverlo). */
    public boolean hasReservedStock() {
        return this == ACEPTADO || this == EN_PREPARACION;
    }
}
