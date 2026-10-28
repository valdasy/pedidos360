package cl.duoc.pedidos360.mqadmin.security;

import cl.duoc.pedidos360.mqadmin.controller.MqAdminController;
import cl.duoc.pedidos360.mqadmin.service.DlqReprocessor;
import cl.duoc.pedidos360.mqadmin.service.QueueAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MqAdminController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "pedidos360.security.enabled=true",
        "pedidos360.security.issuer=https://login.microsoftonline.com/test/v2.0",
        "pedidos360.security.audiences=api-test"})
class SecurityRulesTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    QueueAdminService admin;
    @MockitoBean
    DlqReprocessor reprocessor;

    @Test
    void soloAdmin() throws Exception {
        mvc.perform(get("/api/mq-admin/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mq-admin/alerts").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Operador"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/mq-admin/alerts").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Admin"))))
                .andExpect(status().isOk());
    }
}
