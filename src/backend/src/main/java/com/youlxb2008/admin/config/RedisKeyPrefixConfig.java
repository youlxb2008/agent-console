package com.youlxb2008.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis key 统一前缀配置。
 * <p>
 * 配置 {@code redis.key-prefix}（env {@code REDIS_KEY_PREFIX}）后，通过
 * {@link BeanPostProcessor} 给容器内所有 key 序列化器为 {@link StringRedisSerializer}
 * 的 {@link RedisTemplate}（即自动配置的 StringRedisTemplate）换上带前缀的 key
 * serializer，业务侧（ApiKeyService / AuditService / RateLimitFilter）零改动即全量生效。
 * 未配置（空）时不做任何改动，行为与默认完全一致。
 * <p>
 * 用 BeanPostProcessor 而非重建 bean 的原因：dev profile 排除了 Redis 自动配置
 * （无 Redis 降级本地缓存），后处理器不依赖 {@code RedisConnectionFactory}，两种环境都安全。
 * 默认 {@code RedisTemplate&lt;Object,Object&gt;} 的 key 是 JDK 序列化，不受影响。
 */
@Configuration
public class RedisKeyPrefixConfig {

	@Bean
	public static BeanPostProcessor redisKeyPrefixPostProcessor(
			@Value("${redis.key-prefix:}") String keyPrefix) {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (keyPrefix == null || keyPrefix.isBlank()) {
					return bean;
				}
				if (bean instanceof RedisTemplate<?, ?> template
						&& template.getKeySerializer() instanceof StringRedisSerializer) {
					PrefixedStringRedisSerializer serializer = new PrefixedStringRedisSerializer(keyPrefix);
					template.setKeySerializer(serializer);
					template.setHashKeySerializer(serializer);
				}
				return bean;
			}
		};
	}
}
