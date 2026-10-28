package cl.duoc.pedidos360.orders.order;

import cl.duoc.pedidos360.orders.catalog.CatalogClient;
import cl.duoc.pedidos360.orders.security.CurrentUser;
import cl.duoc.pedidos360.orders.shared.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Reglas de negocio del rol Cliente en OrderService. */
class ClienteOwnershipTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final OrderService service = new OrderService(
            repository, catalog, mock(ApplicationEventPublisher.class), currentUser);

    private Order deAna;

    @BeforeEach
    void setUp() {
        deAna = new Order("Ana", "ana@ejemplo.cl");
        when(repository.findById(1L)).thenReturn(Optional.of(deAna));
    }

    private void loggedAs(String email, String... roles) {
        when(currentUser.get()).thenReturn(Optional.of(new CurrentUser.User(email, email, Set.of(roles))));
    }

    @Test
    void clienteVeSuPedido() {
        loggedAs("ana@ejemplo.cl", "Cliente");
        assertThat(service.get(1L).clienteEmail()).isEqualTo("ana@ejemplo.cl");
    }

    @Test
    void clienteNoVePedidoAjeno() {
        loggedAs("bruno@ejemplo.cl", "Cliente");
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void clienteListaSoloLoSuyo_aunqueFiltrePorOtroEmail() {
        loggedAs("ana@ejemplo.cl", "Cliente");
        when(repository.findByClienteEmailIgnoreCase(eq("ana@ejemplo.cl"), any(Sort.class))).thenReturn(List.of(deAna));
        service.list(null, "otro@ejemplo.cl");
        verify(repository).findByClienteEmailIgnoreCase(eq("ana@ejemplo.cl"), any(Sort.class));
        verify(repository, never()).findByClienteEmailIgnoreCase(eq("otro@ejemplo.cl"), any(Sort.class));
    }

    @Test
    void clientePuedeCancelarSuPedidoCreado() {
        loggedAs("ana@ejemplo.cl", "Cliente");
        assertThat(service.changeStatus(1L, OrderStatus.CANCELADO).estado()).isEqualTo(OrderStatus.CANCELADO);
    }

    @Test
    void clienteNoPuedeAceptar() {
        loggedAs("ana@ejemplo.cl", "Cliente");
        assertThatThrownBy(() -> service.changeStatus(1L, OrderStatus.ACEPTADO)).isInstanceOf(ForbiddenException.class);
        verify(catalog, never()).reserve(any(), any());
    }

    @Test
    void operadorPuedeAceptarCualquierPedido() {
        loggedAs("op@ejemplo.cl", "Operador");
        assertThat(service.changeStatus(1L, OrderStatus.ACEPTADO).estado()).isEqualTo(OrderStatus.ACEPTADO);
        verify(catalog).reserve(any(), any());
    }

    @Test
    void sinSeguridad_noSeFiltraPorUsuario() {
        when(currentUser.get()).thenReturn(Optional.empty());
        assertThat(service.get(1L)).isNotNull();
        verify(repository, never()).findByClienteEmailIgnoreCase(anyString(), any(Sort.class));
    }
}
