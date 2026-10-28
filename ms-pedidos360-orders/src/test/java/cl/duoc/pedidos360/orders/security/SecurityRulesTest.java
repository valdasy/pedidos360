package cl.duoc.pedidos360.orders.security;

import cl.duoc.pedidos360.orders.controller.CommandController;
import cl.duoc.pedidos360.orders.messaging.CommandPublisher;
import cl.duoc.pedidos360.orders.order.OrderController;
import cl.duoc.pedidos360.orders.order.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reglas de autorización por rol (Etapa 6), sin depender de Entra ID:
 * jwt() simula un token ya validado con los roles indicados.
 */
@WebMvcTest(controllers = {OrderController.class, CommandController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "pedidos360.security.enabled=true",
        "pedidos360.security.issuer=https://login.microsoftonline.com/test/v2.0",
        "pedidos360.security.audiences=api-test"})
class SecurityRulesTest {

    private static final String ORDER = """
            {"clienteNombre":"Ana","clienteEmail":"ana@ejemplo.cl","items":[{"productoId":1,"cantidad":1}]}""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService orderService;
    @MockitoBean
    CommandPublisher publisher;
    @MockitoBean
    CurrentUser currentUser;

    private static JwtRequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void sinToken_401() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSinRolesConocidos_403() throws Exception {
        mvc.perform(get("/api/orders").with(jwt())).andExpect(status().isForbidden());
    }

    @Test
    void losTresRoles_puedenListar() throws Exception {
        for (String role : new String[]{"Admin", "Operador", "Cliente"}) {
            mvc.perform(get("/api/orders").with(as(role))).andExpect(status().isOk());
        }
    }

    @Test
    void crearPedido_soloClienteUOperador() throws Exception {
        mvc.perform(post("/api/orders").with(as("Cliente")).contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/orders").with(as("Operador")).contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/orders").with(as("Admin")).contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isForbidden());
    }

    @Test
    void cambiarEstado_pasaElFiltroParaLosTresRoles() throws Exception {
        // El filtro deja pasar; las restricciones del Cliente las aplica OrderService
        for (String role : new String[]{"Admin", "Operador", "Cliente"}) {
            mvc.perform(patch("/api/orders/1/status").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"CANCELADO\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void comandosDePrueba_soloAdmin() throws Exception {
        String email = "{\"pedidoId\":1,\"email\":\"a@b.cl\",\"asunto\":\"x\",\"mensaje\":\"x\"}";
        mvc.perform(post("/api/orders/commands/email").with(as("Operador"))
                        .contentType(MediaType.APPLICATION_JSON).content(email))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/commands/email").with(as("Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(email))
                .andExpect(status().isAccepted());
    }
}
