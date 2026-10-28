package cl.duoc.pedidos360.catalog.security;

import cl.duoc.pedidos360.catalog.product.ProductController;
import cl.duoc.pedidos360.catalog.product.ProductService;
import cl.duoc.pedidos360.catalog.stock.StockController;
import cl.duoc.pedidos360.catalog.stock.StockReservation;
import cl.duoc.pedidos360.catalog.stock.StockService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ProductController.class, StockController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "pedidos360.security.enabled=true",
        "pedidos360.security.issuer=https://login.microsoftonline.com/test/v2.0",
        "pedidos360.security.audiences=api-test"})
class SecurityRulesTest {

    private static final String PRODUCT = """
            {"sku":"X-1","nombre":"X","precio":100,"stock":1}""";
    private static final String RESERVATION = """
            {"pedidoId":1,"items":[{"productoId":1,"cantidad":1}]}""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ProductService productService;
    @MockitoBean
    StockService stockService;

    private static JwtRequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void sinToken_401() throws Exception {
        mvc.perform(get("/api/catalog/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void verProductos_losTresRoles() throws Exception {
        for (String role : new String[]{"Admin", "Operador", "Cliente"}) {
            mvc.perform(get("/api/catalog/products").with(as(role))).andExpect(status().isOk());
        }
    }

    @Test
    void crearProducto_soloAdmin() throws Exception {
        mvc.perform(post("/api/catalog/products").with(as("Operador"))
                .contentType(MediaType.APPLICATION_JSON).content(PRODUCT)).andExpect(status().isForbidden());
        mvc.perform(post("/api/catalog/products").with(as("Admin"))
                .contentType(MediaType.APPLICATION_JSON).content(PRODUCT)).andExpect(status().isCreated());
    }

    @Test
    void reservarStock_operadorOAdmin_noCliente() throws Exception {
        when(stockService.reserve(any(), any())).thenReturn(new StockReservation(1L, List.of()));
        mvc.perform(post("/api/catalog/stock/reservations").with(as("Cliente"))
                .contentType(MediaType.APPLICATION_JSON).content(RESERVATION)).andExpect(status().isForbidden());
        mvc.perform(post("/api/catalog/stock/reservations").with(as("Operador"))
                .contentType(MediaType.APPLICATION_JSON).content(RESERVATION)).andExpect(status().isCreated());
    }
}
