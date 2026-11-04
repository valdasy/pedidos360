package cl.duoc.pedidos360.mqadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Propiedades pedidos360.mq.* de application.properties. */
@ConfigurationProperties(prefix = "pedidos360.mq")
public record MqAdminProperties(
        /** Una URL de la API de Management por nodo del clúster; se usan en orden. */
        List<String> managementUrls,
        String username,
        String password,
        int maxReprocess,
        Alerts alerts) {

    public record Alerts(int dlqCritical, int backlogWarning, long checkIntervalMs) {
    }
}
