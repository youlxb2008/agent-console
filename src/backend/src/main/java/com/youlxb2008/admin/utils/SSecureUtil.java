package com.youlxb2008.admin.utils;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

public class SSecureUtil {
	private static final String KEY = "sbzgyxzmsbzsylzl"; // 密钥长度需符合AES要求
	private static final AES aes = SecureUtil.aes(KEY.getBytes());

	public static void main(String[] args) {
        
        String original = "测试一下哈哈哈";
        System.out.println("原文: " + original);
        // ✅ 加密 → 得到字节数组 → 转为 Base64 字符串（安全可读）
        String encryptedBase64 = encrypt(original);
        System.out.println("加密(Base64): " + encryptedBase64);

        // ✅ 解密 ← 先 Base64 解码回字节数组 ← 再解密
        String decrypted = decrypt("cQGHmCdqH60uOwwhB5jaCBCT+ooNkMY6hP41P6xBCopoFDiMO6AzGoAsGVigrxGwvgTWfel3v19vNhGVd+U83OykYtJRta9Q6P1XMgX2XeOuEmmEEWNcKjDVx9bsf13FT/yfDOnjxRpCiIao/DG4rK1i1jBzIXnt0NNaptaBNYSIyAvrOLPloOS78Tnomyb2qaTzoQLpcLTtup94ZxQ8AaY+aF3mwm9kp1Ymfs3lnLVKJvtPZb3mTffOJ/OqAnnQyMLpRQJd3HL5JRGHy7GYrj5GtEgoNMUHggRmrTTlmUyVEBtYZEstdTt9dD75YeqBeOIfss/QblTt5mylGVuzumFoDESd/P+D77wZR5sGeMaaLLbGT5jxoOp7V20ev8MdyjcYmoQK030UeOXDkc+kqxUXEIL76EWCE00ICTPs/HOx1UFIogcOZKRrg4ORNE66XM+YD0tLAwofTwT2yTeaMblQUNvk+DpgXtWcpBolkVyQ11hpWalVgRX6c6djoPKXOucXFBNstG+EI+4zaupCLU+SSi1YwTnjaRlVYmQKdQTgaAlkuSOH5IvIIlYOEBv1hZIrcbyAJtnIq6yPWel9o2sqf1RwXfcu3iTSbomXb7HLoIGjISL3fzkDlDx/BwpCOKIg60TXyY8WBtoWmeujRhG38QG+wnQeWmgMFT4DgT2P9X7uUeUWYYFGNmZ6AL5dkQu4Jf+kr/A6qWEVKm//vsRoWvAW6p/3AKb15OC07o2gsbR/ynUee48LlUFLWAOcOLI3eZyWRKojxkPkNlAgbyB31xCZqMMGTWjZSsJagFRZYPHYHGnCYooWbrmVqlFMUBEtQo0Zj9FNKUSTWCZjFw==");
        System.out.println("解密: " + decrypted);
    }
	
	public static String encrypt(String content) {
		return aes.encryptBase64(content);
	}
	
	public static String decrypt(String encryptStr) { 
		return aes.decryptStr(encryptStr);
	}
}
