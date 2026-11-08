package com.youlxb2008.admin.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 带统一前缀的 Redis key 序列化器。
 * <p>
 * 多环境共用同一 Redis 实例时（如测试 db0 / 验收 db1），除 database 编号隔离外，
 * 再为所有 key 加环境前缀（如 {@code agc:staging:}），双保险防止串环境读写。
 * 前缀来源：{@code redis.key-prefix}（env {@code REDIS_KEY_PREFIX}），为空时行为与
 * {@link StringRedisSerializer} 完全一致。
 * <ul>
 *   <li>serialize：{@code key} → {@code prefix + key}</li>
 *   <li>deserialize：剥掉前缀；无前缀的历史 key 原样返回（兼容滚动升级）</li>
 * </ul>
 */
public class PrefixedStringRedisSerializer extends StringRedisSerializer {

	private final byte[] prefixBytes;

	public PrefixedStringRedisSerializer(String prefix) {
		this.prefixBytes = (prefix == null ? "" : prefix).getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public byte[] serialize(String key) {
		if (key == null) {
			return null;
		}
		byte[] raw = super.serialize(key);
		if (prefixBytes.length == 0) {
			return raw;
		}
		byte[] out = new byte[prefixBytes.length + raw.length];
		System.arraycopy(prefixBytes, 0, out, 0, prefixBytes.length);
		System.arraycopy(raw, 0, out, prefixBytes.length, raw.length);
		return out;
	}

	@Override
	public String deserialize(byte[] bytes) {
		if (bytes == null) {
			return null;
		}
		if (prefixBytes.length > 0 && startsWith(bytes, prefixBytes)) {
			return super.deserialize(Arrays.copyOfRange(bytes, prefixBytes.length, bytes.length));
		}
		// 无前缀 key（历史数据或未加前缀环境写入）原样返回
		return super.deserialize(bytes);
	}

	private boolean startsWith(byte[] bytes, byte[] prefix) {
		if (bytes.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (bytes[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}
}
