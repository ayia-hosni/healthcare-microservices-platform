package com.healthplatform.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Spring's autoconfigured RedisCacheManager defaults @Cacheable values to
 * JdkSerializationRedisSerializer, which throws IllegalArgumentException on any DTO that
 * isn't java.io.Serializable — every response record cached across this platform (e.g.
 * PatientResponse, DoctorResponse) hits this. Switching to JSON serialization avoids the
 * Serializable requirement entirely and is what every other service already emits over REST.
 * Reuses Spring's own ObjectMapper bean (rather than GenericJackson2JsonRedisSerializer's
 * default no-arg constructor, which builds an unconfigured one) so java.time types like
 * LocalDate serialize correctly via the JavaTimeModule already registered on it.
 */
@Configuration
@ConditionalOnClass(RedisConnectionFactory.class)
public class RedisCacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer jsonRedisCacheCustomizer(ObjectMapper objectMapper) {
        var serializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        return builder -> builder.cacheDefaults(
                RedisCacheConfiguration.defaultCacheConfig()
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer))
        );
    }
}
