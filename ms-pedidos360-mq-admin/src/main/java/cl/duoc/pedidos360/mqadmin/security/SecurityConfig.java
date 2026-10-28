package cl.duoc.pedidos360.mqadmin.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Seguridad de ms-mq-admin (Etapa 6): todas las operaciones son solo para Admin.
 * Con SECURITY_ENABLED=false todo queda abierto (modo práctica).
 */
@Slf4j
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${pedidos360.security.enabled}") boolean enabled,
            @Value("${pedidos360.security.issuer}") String issuer,
            @Value("${pedidos360.security.audiences}") List<String> audiences) throws Exception {

        http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!enabled) {
            log.warn("SEGURIDAD DESACTIVADA (SECURITY_ENABLED=false): todos los endpoints son públicos");
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(a -> a
                    .requestMatchers("/actuator/health/**", "/actuator/info",
                            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    .requestMatchers("/api/mq-admin/**").hasRole(EntraJwt.ADMIN)
                    .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(jwt -> jwt
                    .decoder(EntraJwt.decoder(issuer, audiences))
                    .jwtAuthenticationConverter(EntraJwt.rolesConverter())));

        log.info("Seguridad activa: issuer={} audiences={}", issuer, audiences);
        return http.build();
    }
}
