package com.youlxb2008.admin.modules.system.manager;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.exceptions.ParamsException;
import com.youlxb2008.admin.modules.system.dto.UserTokenPair;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.modules.system.entity.UserPermission;
import com.youlxb2008.admin.modules.system.entity.UserRefreshToken;
import com.youlxb2008.admin.modules.system.mapper.UserRefreshTokenMapper;
import com.youlxb2008.admin.modules.system.service.UserPermissionService;
import com.youlxb2008.admin.modules.system.service.UserService;
import com.youlxb2008.admin.modules.system.vo.UserLoginReq;
import com.youlxb2008.admin.modules.system.vo.UserLoginResp;
import com.youlxb2008.admin.modules.system.vo.UserRefreshTokenVO;
import com.youlxb2008.admin.utils.jwt.utils.JwtUtil;
import com.youlxb2008.admin.utils.jwt.utils.RefreshTokenUtils;

import cn.hutool.core.date.DateUtil;
import cn.hutool.crypto.digest.BCrypt;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 认证编排 Manager
 * 负责登录鉴权、token 颁发与刷新、登出等跨 Service 编排逻辑
 */
@Slf4j
@Component
public class AuthManager {

	@Autowired
	private UserService userService;

	@Autowired
	private UserPermissionService permissionService;

	@Autowired
	private UserRefreshTokenMapper refreshTokenMapper;

	@Autowired
	private JwtUtil jwtUtil;

	@Autowired
	private PermissionManager permissionManager;

	/**
	 * 用户登录
	 * 1. 验证用户名密码
	 * 2. 检查账号状态
	 * 3. 更新最后登录时间
	 * 4. 颁发 access + refresh token
	 * 5. 组装 roles / permissions 返回
	 */
	public UserLoginResp login(UserLoginReq loginDTO, HttpServletRequest request) {
		String username = loginDTO.getUsername();

		// 1. 验证用户（只按用户名查询，不限制 userType，所有类型用户均可登录）
		User user = userService.getOne(
				Wrappers.<User>lambdaQuery().eq(User::getUsername, username),
				true);
		if (user == null || !BCrypt.checkpw(loginDTO.getPassword(), user.getPassword())) {
			throw new ParamsException("用户名或密码错误");
		}
		if (user.getStatus() != null && user.getStatus() != 0) {
			throw new ParamsException("账号已被锁定，禁止登录！");
		}

		// 2. 更新最后登录时间
		user.setLastLoginTime(new Date());
		userService.updateById(user);

		// 3. 颁发 token
		UserTokenPair tokenPair = issueNewTokenPair(user, request, null);

		// 4. 组装响应
		UserLoginResp resp = new UserLoginResp();
		resp.setNickname(user.getNickname());
		resp.setUsername(user.getUsername());
		resp.setAvatar(user.getAvatar());
		resp.setAccessToken(tokenPair.getAccessToken());
		resp.setExpires(tokenPair.getExpires());
		resp.setRefreshToken(tokenPair.getRefreshToken());

		// 5. 查询权限并拆分 roles / permissions
		List<UserPermission> permissionByUsername = permissionManager.getPermissionsByUsername(username);
		List<String> roles = permissionByUsername.stream()
				.filter(p -> p.getType() != null && p.getType() == 1)
				.map(UserPermission::getPermissionKey)
				.filter(StringUtils::hasText)
				.collect(Collectors.toList());
		List<String> permissions = permissionByUsername.stream()
				.filter(p -> p.getType() != null && (p.getType() == 2 || p.getType() == 3))
				.map(UserPermission::getPermissionKey)
				.filter(StringUtils::hasText)
				.collect(Collectors.toList());
		resp.setRoles(roles);
		resp.setPermissions(permissions);
		// 检查是否需要强制修改密码
		resp.setMustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()));
		return resp;
	}

	/**
	 * 刷新 token（轮换式）
	 * 验证旧 refresh token → 删除旧 token → 颁发新 token
	 */
	@DSTransactional
	public UserTokenPair refreshTokens(String rawRefreshToken, HttpServletRequest request) {
		if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
			throw new ParamsException("Refresh token required");
		}

		String tokenHash = RefreshTokenUtils.hashRefreshToken(rawRefreshToken);
		UserRefreshToken stored = refreshTokenMapper
				.selectOne(new LambdaQueryWrapper<UserRefreshToken>()
						.eq(UserRefreshToken::getTokenHash, tokenHash)
						.isNull(UserRefreshToken::getRevokedAt));

		if (stored == null) {
			throw new ParamsException("Invalid or expired refresh token");
		}

		User user = userService.getById(stored.getUserId());
		if (user == null || (user.getStatus() != null && user.getStatus() != 0)) {
			refreshTokenMapper.delete(
					new LambdaQueryWrapper<UserRefreshToken>().eq(UserRefreshToken::getUserId, stored.getUserId()));
			throw new ParamsException("User disabled");
		}

		// 删除旧的（轮换）
		refreshTokenMapper.deleteById(stored.getId());
		return issueNewTokenPair(user, request, stored);
	}

	/**
	 * 用户登出：使指定的 refresh token 失效
	 */
	public void logout(String rawRefreshToken) {
		if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
			throw new ParamsException("Refresh token is required for logout");
		}

		String tokenHash = RefreshTokenUtils.hashRefreshToken(rawRefreshToken);
		UserRefreshToken stored = refreshTokenMapper.selectOne(new LambdaQueryWrapper<UserRefreshToken>()
				.eq(UserRefreshToken::getTokenHash, tokenHash).gt(UserRefreshToken::getExpiresAt, LocalDateTime.now()));

		if (stored != null) {
			stored.setRevokedAt(LocalDateTime.now());
			refreshTokenMapper.updateById(stored);
		}
	}

	// ========== 私有方法 ==========

	/**
	 * 撤销指定用户的所有 refresh token（用于改密码、踢下线等场景）
	 */
	public void revokeAllByUserId(Long userId) {
		List<UserRefreshToken> tokens = refreshTokenMapper.selectList(
				new LambdaQueryWrapper<UserRefreshToken>()
						.eq(UserRefreshToken::getUserId, userId)
						.isNull(UserRefreshToken::getRevokedAt));
		LocalDateTime now = LocalDateTime.now();
		tokens.forEach(t -> {
			t.setRevokedAt(now);
			refreshTokenMapper.updateById(t);
		});
	}

	/**
	 * 查询指定用户的所有在线设备（未撤销且未过期的 refresh token）
	 * @param userId 用户 ID
	 * @param currentDeviceName 当前请求的设备名称，用于标记"当前设备"
	 * @return 在线设备列表
	 */
	public List<UserRefreshTokenVO> getOnlineDevices(Long userId, String currentDeviceName) {
		// 查询未撤销且未过期的 refresh token，按创建时间倒序
		List<UserRefreshToken> tokens = refreshTokenMapper.selectList(
				new LambdaQueryWrapper<UserRefreshToken>()
						.eq(UserRefreshToken::getUserId, userId)
						.isNull(UserRefreshToken::getRevokedAt)
						.gt(UserRefreshToken::getExpiresAt, LocalDateTime.now())
						.orderByDesc(UserRefreshToken::getCreatedAt));

		// 转换为 VO，隐藏敏感字段
		return tokens.stream().map(t -> {
			UserRefreshTokenVO vo = new UserRefreshTokenVO();
			vo.setId(t.getId());
			vo.setDeviceName(t.getDeviceName());
			vo.setIpAddress(t.getIpAddress());
			vo.setUserAgent(t.getUserAgent());
			vo.setExpiresAt(t.getExpiresAt());
			vo.setCreatedAt(t.getCreatedAt());
			// 判断是否为当前设备
			vo.setCurrent(currentDeviceName != null && currentDeviceName.equals(t.getDeviceName()));
			return vo;
		}).collect(Collectors.toList());
	}

	/**
	 * 踢下线指定设备（撤销指定 refresh token）
	 * 不能踢自己当前设备
	 * @param userId 当前用户 ID
	 * @param tokenId 要踢下线的 token ID
	 */
	public void kickDevice(Long userId, Long tokenId) {
		UserRefreshToken token = refreshTokenMapper.selectById(tokenId);
		if (token == null) {
			throw new ParamsException("Token 不存在");
		}
		// 校验 token 归属当前用户
		if (!token.getUserId().equals(userId)) {
			throw new ForbiddenException("不能踢下线其他用户的设备");
		}
		// 校验 token 尚未撤销
		if (token.getRevokedAt() != null) {
			throw new ParamsException("该设备已下线");
		}
		// 设置撤销时间，使 token 失效
		token.setRevokedAt(LocalDateTime.now());
		refreshTokenMapper.updateById(token);
	}

	/**
	 * 根据请求的 User-Agent 解析设备名称
	 */
	public String getDeviceName(HttpServletRequest request) {
		String userAgent = request.getHeader("User-Agent");
		if (userAgent != null) {
			if (userAgent.contains("iPhone"))
				return "iPhone";
			if (userAgent.contains("Android"))
				return "Android";
			if (userAgent.contains("Chrome"))
				return "Chrome";
			if (userAgent.contains("Safari"))
				return "Safari";
		}
		return "Unknown Device";
	}

	private UserTokenPair issueNewTokenPair(User user, HttpServletRequest request, UserRefreshToken originalToken) {
		// 1. 生成 access token
		Date now = new Date();
		Date accessTokenExpire = DateUtil.offsetMinute(now, JwtUtil.EXPIRE);
		String accessToken = jwtUtil.generateToken(user.getUsername(), accessTokenExpire);

		// 2. 生成 refresh token（明文返回给前端，哈希入库）
		String rawRefreshToken = RefreshTokenUtils.generateRefreshToken();
		String refreshTokenHash = RefreshTokenUtils.hashRefreshToken(rawRefreshToken);

		// 3. 组装 refresh token 实体
		// 设备信息来源：刷新场景从原 token 复制（设备未变），登录场景从当前请求解析
		UserRefreshToken newToken = new UserRefreshToken();
		newToken.setUserId(user.getId());
		newToken.setTokenHash(refreshTokenHash);
		if (originalToken != null) {
			// 刷新场景：复用原 token 的设备信息
			newToken.setDeviceName(originalToken.getDeviceName());
			newToken.setDeviceId(originalToken.getDeviceId());
			newToken.setIpAddress(originalToken.getIpAddress());
			newToken.setUserAgent(originalToken.getUserAgent());
		} else {
			// 登录场景：从当前请求解析设备信息
			newToken.setDeviceName(getDeviceName(request));
			newToken.setIpAddress(request.getRemoteAddr());
			newToken.setUserAgent(request.getHeader("User-Agent"));
		}
		newToken.setExpiresAt(LocalDateTime.now().plusDays(7));
		newToken.setCreatedAt(LocalDateTime.now());
		refreshTokenMapper.insert(newToken);

		// 4. 组装返回对象
		UserTokenPair tokens = new UserTokenPair();
		tokens.setAccessToken(accessToken);
		tokens.setRefreshToken(rawRefreshToken);
		tokens.setExpires(accessTokenExpire.getTime());
		return tokens;
	}
}
