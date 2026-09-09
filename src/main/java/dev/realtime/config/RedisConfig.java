package dev.realtime.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Plain-string (de)serialization for both keys and values.
 *
 * <p>The auto-configured {@code ReactiveRedisTemplate} defaults to JDK serialization for
 * values, which would make every fingerprint key and stream entry unreadable via
 * {@code redis-cli} — exactly the tool M1's own exit criteria depends on. Keeping
 * everything as plain UTF-8 strings means what you see in {@code XRANGE} output is
 * exactly what was sent, no decoding required.
 */
@Configuration
public class RedisConfig {

    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {

        RedisSerializationContext<String, String> context = RedisSerializationContext
                .<String, String>newSerializationContext(StringRedisSerializer.UTF_8)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }
}
