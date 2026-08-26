package com.healthplatform.common.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Spring Boot's autoconfigured RedisCacheManager defaults @Cacheable values to
 * JdkSerializationRedisSerializer, which throws IllegalArgumentException on any DTO that
 * isn't java.io.Serializable — every response record cached across this platform (e.g.
 * PatientResponse, DoctorResponse) hits this. A RedisCacheManagerBuilderCustomizer bean was
 * tried first (the documented extension point for this), but empirically its serializer never
 * won out over Boot's own default in the deployed build, so this defines the CacheManager
 * directly instead — Boot's CacheAutoConfiguration backs off entirely once a CacheManager
 * bean already exists (@ConditionalOnMissingBean), removing any ordering ambiguity.
 *
 * Neither of GenericJackson2JsonRedisSerializer's two constructor options works alone here:
 * passing Spring's own REST-serialization ObjectMapper (has JavaTimeModule, no polymorphic
 * type info) caches values that read back as a generic LinkedHashMap instead of the original
 * DTO type — confirmed via ClassCastException on a cache hit, since one CacheManager serves
 * many different DTO types and needs per-value type info embedded in the JSON to know what to
 * deserialize into. The no-arg constructor (has its own default-typing setup, no
 * JavaTimeModule) then fails serializing LocalDate — confirmed via SerializationException on
 * PatientResponse.dateOfBirth. So this builds a dedicated ObjectMapper combining both: an
 * explicit JavaTimeModule registration for java.time types, and activateDefaultTyping (Jackson's
 * public equivalent of what the no-arg constructor sets up internally) so the concrete type
 * survives the round trip. allowIfSubType(Object.class) is fine for a PolymorphicTypeValidator
 * here since this cache only ever (de)serializes our own trusted DTOs written by our own
 * services, never untrusted external input.
 *
 * DefaultTyping.EVERYTHING, not NON_FINAL: every cached response here is a Java record, and
 * records are implicitly final. NON_FINAL's whole premise is "skip type info for final classes,
 * their static and runtime type can't diverge" — true for a *field* typed as that exact record,
 * but not for the *root* value here, whose only declared type at the call site is Object
 * (GenericJackson2JsonRedisSerializer.serialize(Object)). Confirmed locally: NON_FINAL silently
 * wrote zero type info for a record root value, so deserialize had nothing to recover the
 * concrete type from and failed outright — EVERYTHING fixes that by not special-casing finality.
 */
@Configuration
@ConditionalOnClass(RedisConnectionFactory.class)
@EnableConfigurationProperties(CacheProperties.class)
public class RedisCacheConfig {

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory, CacheProperties cacheProperties) {
        ObjectMapper cacheObjectMapper = new ObjectMapper();
        cacheObjectMapper.registerModule(new JavaTimeModule());
        cacheObjectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfSubType(Object.class).build(),
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);

        var serializer = new GenericJackson2JsonRedisSerializer(cacheObjectMapper);
        var config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        CacheProperties.Redis redisProps = cacheProperties.getRedis();
        if (redisProps.getTimeToLive() != null) {
            config = config.entryTtl(redisProps.getTimeToLive());
        }
        if (!redisProps.isCacheNullValues()) {
            config = config.disableCachingNullValues();
        }
        if (!redisProps.isUseKeyPrefix()) {
            config = config.disableKeyPrefix();
        }

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
