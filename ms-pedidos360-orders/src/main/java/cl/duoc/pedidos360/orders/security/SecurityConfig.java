package cl.duoc.pedidos360.orders.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Seguridad del microservicio (Etapa 6).
 *
 * 1. AUTENTICACIÓN: cada petición debe traer "Authorization: Bearer <JWT>" emitido por
 *    Microsoft Entra ID. Se valida firma, expiración, issuer (tenant) y audience (nuestra API).
 * 2. AUTORIZACIÓN: los roles vienen en el claim "roles" del token (App roles de Entra) y
 *    se exigen por endpoint, más abajo.
 *
 * Con SECURITY_ENABLED=false (por defecto, mientras Entra no está configurado) todo queda
 * abierto para poder desarrollar y practicar en local.
 */
@Slf4j
@Configuration
public class SecurityConfig {

    static final String ADMIN = EntraJwt.ADMIN;
    static final String OPERADOR = EntraJwt.OPERADOR;
    static final String CLIENTE = EntraJwt.CLIENTE;

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${pedidos360.security.enabled}") boolean enabled,
            @Value("${pedidos360.security.issuer}") String issuer,
            @Value("${pedidos360.security.audiences}") List<String> audiences) throws Exception {

        http.csrf(csrf -> csrf.disable())   // API sin cookies de sesión: CSRF no aplica
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!enabled) {
            log.warn("SEGURIDAD DESACTIVADA (SECURITY_ENABLED=false): todos los endpoints son públicos");
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(a -> a
                    .requestMatchers("/actuator/health/**", "/actuator/info",
                            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    // Pruebas de mensajería (Etapa 2): solo administradores
                    .requestMatchers("/api/orders/commands/**").hasRole(ADMIN)
                    // Crear pedidos: solo cliente u operador (regla del caso)
                    .requestMatchers(HttpMethod.POST, "/api/orders").hasAnyRole(CLIENTE, OPERADOR)
                    // Leer, editar, eliminar y cambiar estado: los tres roles. El Cliente solo
                    // sobre sus propios pedidos y solo puede cancelarlos (se valida en OrderService).
                    .requestMatchers("/api/orders/**").hasAnyRole(ADMIN, OPERADOR, CLIENTE)
                    .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(jwt -> jwt
                    .decoder(EntraJwt.decoder(issuer, audiences))
                    .jwtAuthenticationConverter(EntraJwt.rolesConverter())));

        log.info("Seguridad activa: issuer={} audiences={}", issuer, audiences);
        return http.build();
    }
}
