package cl.duoc.pedidos360.mqadmin.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.List;

/** Cómo interpretar los tokens de Microsoft Entra ID. */
public final class EntraJwt {

    /** Valores de los App roles definidos en el registro de la API en Entra. */
    public static final String ADMIN = "Admin";
    public static final String OPERADOR = "Operador";
    public static final String CLIENTE = "Cliente";

    private EntraJwt() {
    }

    /**
     * Decodificador que descarga las claves públicas de Entra (JWKS) desde
     * {issuer}/.well-known/openid-configuration y valida:
     *  - firma y expiración (exp/nbf)
     *  - iss = https://login.microsoftonline.com/<tenant>/v2.0
     *  - aud = nuestra API (client id o api://client-id)
     * Es "perezoso": se conecta a Entra con la primera petición, no al arrancar.
     */
    public static JwtDecoder decoder(String issuer, List<String> audiences) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
            OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                    JwtClaimNames.AUD, aud -> aud != null && aud.stream().anyMatch(audiences::contains));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer), audience));
            return decoder;
        });
    }

    /** Claim "roles" -> ROLE_Admin, ROLE_Operador, ROLE_Cliente (para usar hasRole). */
    public static JwtAuthenticationConverter rolesConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
