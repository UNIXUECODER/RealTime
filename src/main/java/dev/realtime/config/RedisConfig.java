package dev.realtime.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Plain-string (de)serialization for both keys and values.
 *
 * <p>Naming the bean {@code reactiveStringRedisTemplate} directly satisfies
 * {@code @ConditionalOnMissingBean(name = "reactiveStringRedisTemplate")} in
 * {@code DataRedisReactiveAutoConfiguration}. This prevents Spring Boot from creating
 * a redundant second template, making {@code @Primary} unnecessary.
 */
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {

        RedisSerializationContext<String, String> context = RedisSerializationContext
                .<String, String>newSerializationContext(StringRedisSerializer.UTF_8)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }
}
