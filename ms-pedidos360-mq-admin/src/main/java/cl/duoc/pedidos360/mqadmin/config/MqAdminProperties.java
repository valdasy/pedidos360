package cl.duoc.pedidos360.mqadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Propiedades pedidos360.mq.* de application.properties. */
@ConfigurationProperties(prefix = "pedidos360.mq")
public record MqAdminProperties(
        String managementUrl,
        String username,
        String password,
        int maxReprocess,
        Alerts alerts) {

    public record Alerts(int dlqCritical, int backlogWarning, long checkIntervalMs) {
    }
}
