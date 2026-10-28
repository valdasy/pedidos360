package cl.duoc.pedidos360.orders.order;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByEstado(OrderStatus estado, Sort sort);

    List<Order> findByClienteEmailIgnoreCase(String email, Sort sort);
}
