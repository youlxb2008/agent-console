package com.youlxb2008.admin.utils.jwt.utils;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Refresh Token 工具类
 * <p>
 * 使用 HMAC-SHA256 + 外部盐值（密钥）进行确定性哈希，确保： - 相同 raw token 总是生成相同 hash（可用于数据库查询） -
 * 攻击者无法通过 hash 反推原始 token（因密钥保密）
 */
@Slf4j
public class RefreshTokenUtils {

	// 🔒 盐值应从配置注入，此处仅为示例（实际请勿硬编码！）
	private static String SALT = "CHANGE_ME_TO_A_SECURE_RANDOM_64_BYTE_STRING_IN_PRODUCTION";

	/**
	 * 设置盐值（建议在应用启动时从配置加载）
	 */
	public static void setSalt(String salt) {
		if (salt == null || salt.trim().isEmpty()) {
			throw new IllegalArgumentException("Salt must not be empty");
		}
		RefreshTokenUtils.SALT = salt;
	}

	/**
	 * 生成高强度随机 refresh token（128 字符 ≈ 768 bits 熵，远超安全要求）
	 */
	public static String generateRefreshToken() {
		String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
		return RandomUtil.randomString(chars, 128);
	}

	/**
	 * 对原始 refresh token 进行确定性哈希（用于数据库存储） 使用 HMAC-SHA256 算法，密钥为 SALT
	 */
	public static String hashRefreshToken(String rawToken) {
		if (rawToken == null) {
			throw new IllegalArgumentException("Raw token must not be null");
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			SecretKeySpec secretKey = new SecretKeySpec(SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
			mac.init(secretKey);
			byte[] hashBytes = mac.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));
			return java.util.Base64.getEncoder().encodeToString(hashBytes);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			log.error("Hash refresh token failed", e);
			throw new RuntimeException("Failed to hash refresh token", e);
		}
	}

	/**
	 * 验证原始 token 与存储的哈希是否匹配 （实际只需调用 hashRefreshToken 后比较，但保留此方法语义清晰）
	 */
	public static boolean verify(String rawToken, String storedHash) {
		if (rawToken == null || storedHash == null) {
			return false;
		}
		String computedHash = hashRefreshToken(rawToken);
		return computedHash.equals(storedHash);
	}

	// ==================== 测试用 main 方法 ====================
	public static void main(String[] args) {
		// 示例：设置盐值（生产环境应从配置读取）
		setSalt("my-secure-salt-for-refresh-token-hashing-2026");

		String token = generateRefreshToken();
		System.out.println("Raw Token: " + token);

		String hash1 = hashRefreshToken(token);
		String hash2 = hashRefreshToken(token);

		System.out.println("Hash 1: " + hash1);
		System.out.println("Hash 2: " + hash2);
		System.out.println("Hashes equal? " + hash1.equals(hash2)); // 应为 true

		System.out.println("Verify result: " + verify(token, hash1)); // 应为 true
		System.out.println("Verify wrong token: " + verify("wrong", hash1)); // 应为 false
	}
}