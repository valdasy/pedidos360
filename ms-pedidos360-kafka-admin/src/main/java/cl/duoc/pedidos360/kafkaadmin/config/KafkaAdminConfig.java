package cl.duoc.pedidos360.kafkaadmin.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class KafkaAdminConfig {

    /**
     * Cliente de administración de Kafka (crear/describir/modificar/eliminar tópicos, consumer
     * groups y offsets). Es thread-safe: se crea una vez y Spring lo cierra al apagar.
     */
    @Bean(destroyMethod = "close")
    AdminClient adminClient(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                AdminClientConfig.CLIENT_ID_CONFIG, "ms-pedidos360-kafka-admin",
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000));
    }
}
