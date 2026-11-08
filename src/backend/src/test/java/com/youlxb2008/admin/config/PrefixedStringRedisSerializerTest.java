package com.youlxb2008.admin.config;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * Redis key 统一前缀序列化器单测。
 */
class PrefixedStringRedisSerializerTest {

	@Test
	void serializeAddsPrefixAndDeserializeStripsIt() {
		PrefixedStringRedisSerializer serializer = new PrefixedStringRedisSerializer("agc:staging:");
		byte[] bytes = serializer.serialize("ac:key:abc123");
		assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("agc:staging:ac:key:abc123");
		assertThat(serializer.deserialize(bytes)).isEqualTo("ac:key:abc123");
	}

	@Test
	void emptyPrefixBehavesLikePlainSerializer() {
		PrefixedStringRedisSerializer serializer = new PrefixedStringRedisSerializer("");
		assertThat(serializer.serialize("ac:audit:buffer"))
				.isEqualTo("ac:audit:buffer".getBytes(StandardCharsets.UTF_8));
		assertThat(serializer.deserialize(serializer.serialize("ac:audit:buffer"))).isEqualTo("ac:audit:buffer");
	}

	@Test
	void deserializeLegacyKeyWithoutPrefixReturnsAsIs() {
		// 滚动升级兼容：无前缀的历史 key 原样读出，不抛异常不串位
		PrefixedStringRedisSerializer serializer = new PrefixedStringRedisSerializer("agc:staging:");
		byte[] legacy = "ac:key:legacy".getBytes(StandardCharsets.UTF_8);
		assertThat(serializer.deserialize(legacy)).isEqualTo("ac:key:legacy");
	}

	@Test
	void prefixThatIsPrefixOfAnotherKeyDoesNotOverStrip() {
		// key 本身以前缀开头但并非本序列化器写入（长度超出前缀且剩余部分任意）——
		// 剥离规则是"剥一次固定长度前缀"，此处验证前缀恰好相同字符串时只剥一次
		PrefixedStringRedisSerializer serializer = new PrefixedStringRedisSerializer("agc:");
		byte[] bytes = serializer.serialize("agc:inner");
		assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("agc:agc:inner");
		assertThat(serializer.deserialize(bytes)).isEqualTo("agc:inner");
	}

	@Test
	void nullKeyRoundTrip() {
		PrefixedStringRedisSerializer serializer = new PrefixedStringRedisSerializer("p:");
		assertThat(serializer.serialize(null)).isNull();
		assertThat(serializer.deserialize(null)).isNull();
	}
}
