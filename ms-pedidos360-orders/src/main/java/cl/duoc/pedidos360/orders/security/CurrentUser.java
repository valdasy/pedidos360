package cl.duoc.pedidos360.orders.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Datos del usuario autenticado, leídos del JWT de Entra. */
@Component
public class CurrentUser {

    public record User(String email, String name, Set<String> roles) {

        /** Tiene rol Cliente y ningún rol de personal (Operador/Admin). */
        public boolean isOnlyCliente() {
            return roles.contains(EntraJwt.CLIENTE)
                    && !roles.contains(EntraJwt.OPERADOR) && !roles.contains(EntraJwt.ADMIN);
        }
    }

    /** Vacío si no hay token (seguridad desactivada): en ese caso no se filtra por usuario. */
    public Optional<User> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            return Optional.empty();
        }
        Jwt jwt = token.getToken();
        String email = firstNonBlank(jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"), jwt.getClaimAsString("upn"));
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toSet());
        return Optional.of(new User(email, jwt.getClaimAsString("name"), roles));
    }

    /** El token tal cual llegó, para reenviarlo a ms-catalog (token relay). */
    public Optional<String> bearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken token
                ? Optional.of(token.getToken().getTokenValue())
                : Optional.empty();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
