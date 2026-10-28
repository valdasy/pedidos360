package cl.duoc.pedidos360.orders.config;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    /**
     * Convierte los objetos Java a JSON al publicar. Spring Boot detecta este Bean
     * y lo asigna automáticamente al RabbitTemplate.
     */
    @Bean
    public JacksonJsonMessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
