package com.youlxb2008.admin.modules.system.controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.modules.system.dto.UserTokenPair;
import com.youlxb2008.admin.modules.system.entity.UserPermission;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.modules.system.entity.RoleOnlyEntity;
import com.youlxb2008.admin.modules.system.manager.AuthManager;
import com.youlxb2008.admin.modules.system.manager.PermissionManager;
import com.youlxb2008.admin.modules.system.service.UserService;
import com.youlxb2008.admin.modules.system.vo.UserLoginReq;
import com.youlxb2008.admin.modules.system.vo.UserLoginResp;
import com.youlxb2008.admin.modules.system.vo.UserVO;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.CurrentUser;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresAuthentication;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresUser;
import com.youlxb2008.admin.utils.jwt.utils.JwtUtil;
import com.youlxb2008.admin.utils.page.BPage;
import com.youlxb2008.admin.utils.page.PageSearch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping(path = "/system/user")
@Slf4j
@Tag(name = "用户管理", description = "用户登录/CRUD/角色分配")
public class UserController {
	@Autowired
	private UserService userService;

	@Autowired
	private AuthManager authManager;

	@Autowired
	private PermissionManager permissionManager;

	@Operation(summary = "用户登录")
	@PostMapping("/login")
	public ResultVO login(@RequestBody @Validated UserLoginReq loginDTO, HttpServletRequest request,
			HttpServletResponse response) {
		UserLoginResp login = authManager.login(loginDTO, request);

		response.setHeader(JwtUtil.HEADER, login.getAccessToken());
		response.setHeader("Access-control-Expost-Headers", JwtUtil.HEADER);
		log.info("登入成功username={}", loginDTO.getUsername());
		return ResultVO.getSuccessVO("登录成功").setData(login);
	}

	@Operation(summary = "登录测试")
	@RequiresAuthentication
	@PostMapping("/test")
	public ResultVO test() {
		return ResultVO.getSuccessVO("测试成功");
	}

	@Operation(summary = "获取当前用户信息")
	@RequiresUser
	@PostMapping("/getCurrentUser")
	public ResultVO getCurrentUser(@CurrentUser User currentUser) {
		// 从 t_role 表查真实角色（不再错误地从 t_permission 里筛 type=1）
		java.util.Set<String> roleNames = permissionManager.getRoleNamesByUserId(currentUser.getId());

		// 权限 key：从 t_permission 里筛 type=2 或 3 的（菜单权限 + 按钮权限）
		java.util.List<UserPermission> permissionByUsername = permissionManager
				.getPermissionsByUsername(currentUser.getUsername());
		java.util.List<String> permissions = permissionByUsername.stream()
				.filter(p -> p.getType() != null && (p.getType() == 2 || p.getType() == 3))
				.map(UserPermission::getPermissionKey)
				.filter(org.springframework.util.StringUtils::hasText)
				.collect(java.util.stream.Collectors.toList());

		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("id", currentUser.getId());
		data.put("username", currentUser.getUsername());
		data.put("name", currentUser.getNickname());
		data.put("nickname", currentUser.getNickname());
		data.put("email", currentUser.getEmail());
		data.put("phone", currentUser.getPhone());
		data.put("avatar", currentUser.getAvatar());
		data.put("roles", roleNames);
		data.put("permissions", permissions);
		return ResultVO.getSuccessVO("查询成功").setData(data);
	}

	/**
	 * 更新个人资料（当前登录用户自行编辑，无需管理权限）。
	 * <p>
	 * 仅允许修改 nickname / email / phone / avatar 四个字段，
	 * 从 {@link CurrentUser} 取当前用户 ID，防止越权篡改他人资料。
	 *
	 * @param profile     前端提交的个人资料（仅需 nickname/email/phone/avatar 字段）
	 * @param currentUser 当前登录用户（由 {@code @CurrentUser} 注入）
	 */
	@Operation(summary = "更新个人资料")
	@RequiresUser
	@PostMapping("/updateProfile")
	public ResultVO updateProfile(@RequestBody java.util.Map<String, Object> profile, @CurrentUser User currentUser) {
		User user = new User();
		user.setId(currentUser.getId());
		if (profile.containsKey("nickname")) {
			user.setNickname((String) profile.get("nickname"));
		}
		if (profile.containsKey("email")) {
			user.setEmail((String) profile.get("email"));
		}
		if (profile.containsKey("phone")) {
			user.setPhone((String) profile.get("phone"));
		}
		if (profile.containsKey("avatar")) {
			user.setAvatar((String) profile.get("avatar"));
		}
		userService.updateById(user);
		log.info("用户 {} 更新了个人资料", currentUser.getUsername());
		return ResultVO.getSuccessVO("保存成功");
	}

	/** 查看所有用户列表 - 需要 system:user 权限 */
	@Operation(summary = "查询所有用户列表")
	@RequiresPermissions("system:user")
	@GetMapping("/listAll")
	public ResultVO listAll() {
		java.util.List<User> userList = userService.list();
		// 填充角色信息
		permissionManager.fillUserRoles(userList);

		// 转换为 UserVO
		java.util.List<UserVO> voList = userList.stream().map(u -> {
			UserVO vo = new UserVO();
			vo.setId(u.getId());
			vo.setUsername(u.getUsername());
			vo.setNickname(u.getNickname());
			vo.setEmail(u.getEmail());
			vo.setPhone(u.getPhone());
			vo.setAvatar(u.getAvatar());
			vo.setRemark(u.getRemark());
			vo.setStatus(u.getStatus());
			vo.setMustChangePassword(u.getMustChangePassword());
			vo.setLastLoginTime(u.getLastLoginTime());
			vo.setCreateTime(u.getCreatedAt());
			if (u.getRoles() != null) {
				vo.setRoles(u.getRoles().stream()
						.filter(r -> r.getId() != null)
						.map(r -> new UserVO.RoleItem(r.getId(), r.getName(), r.getCode()))
						.collect(Collectors.toList()));
			}
			return vo;
		}).collect(Collectors.toList());

		return ResultVO.getSuccessVO("查询成功").setData(voList);
	}

	/** 查看用户列表 - 需要 system:user 权限 */
	@Operation(summary = "分页查询用户列表")
	@RequiresPermissions("system:user")
	@GetMapping("/list")
	public ResultVO list(User user, PageSearch page) {
		com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
		if (user.getUsername() != null && !user.getUsername().trim().isEmpty()) {
			queryWrapper.like(User::getUsername, user.getUsername().trim());
		}
		if (user.getNickname() != null && !user.getNickname().trim().isEmpty()) {
			queryWrapper.like(User::getNickname, user.getNickname().trim());
		}
		if (user.getPhone() != null && !user.getPhone().trim().isEmpty()) {
			queryWrapper.like(User::getPhone, user.getPhone().trim());
		}
		if (user.getStatus() != null) {
			queryWrapper.eq(User::getStatus, user.getStatus());
		}
		queryWrapper.orderByDesc(User::getId);
		IPage<User> pageData = userService.page(page.toDBPage(), queryWrapper);
		// 填充角色信息（roles 字段为非持久化字段，需额外查询）
		permissionManager.fillUserRoles(pageData.getRecords());

		// 转换为 UserVO（隐藏 password 等敏感字段，统一前端字段命名 createTime）
		// 使用 IPage.convert 直接转换分页对象，避免手动重建 Page
		IPage<UserVO> voPage = pageData.convert(u -> {
			UserVO vo = new UserVO();
			vo.setId(u.getId());
			vo.setUsername(u.getUsername());
			vo.setNickname(u.getNickname());
			vo.setEmail(u.getEmail());
			vo.setPhone(u.getPhone());
			vo.setAvatar(u.getAvatar());
			vo.setRemark(u.getRemark());
			vo.setStatus(u.getStatus());
			vo.setMustChangePassword(u.getMustChangePassword());
			vo.setLastLoginTime(u.getLastLoginTime());
			// Entity 的 createdAt 在 VO 中暴露为 createTime，统一前端字段命名
			vo.setCreateTime(u.getCreatedAt());
			// roles 是 RoleOnlyEntity 列表，转为 RoleItem（包含 id/name/code，供前端渲染和回显）
			if (u.getRoles() != null) {
				vo.setRoles(u.getRoles().stream()
						.filter(r -> r.getId() != null)
						.map(r -> new UserVO.RoleItem(r.getId(), r.getName(), r.getCode()))
						.collect(Collectors.toList()));
			}
			return vo;
		});

		// 使用 BPage 统一分页出参格式（curPage / count / pageSize / list）
		BPage bPage = BPage.of(voPage);
		return ResultVO.getSuccessVO("查询成功").setData(bPage);
	}

	/** 查看用户详情 - 需要 system:user 权限 */
	@Operation(summary = "查询用户详情")
	@RequiresPermissions("system:user")
	@GetMapping("/getById")
	public ResultVO getById(Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		User user = userService.getById(id);
		if (user == null) {
			return ResultVO.getFailedVO("User not found");
		}
		// 填充角色信息（getById 也需要，供分配角色弹窗回显已有角色）
		permissionManager.fillUserRoles(java.util.Collections.singletonList(user));
		// 转换为 UserVO 隐藏敏感字段
		UserVO vo = new UserVO();
		vo.setId(user.getId());
		vo.setUsername(user.getUsername());
		vo.setNickname(user.getNickname());
		vo.setEmail(user.getEmail());
		vo.setPhone(user.getPhone());
		vo.setAvatar(user.getAvatar());
		vo.setRemark(user.getRemark());
		vo.setStatus(user.getStatus());
		vo.setMustChangePassword(user.getMustChangePassword());
		vo.setLastLoginTime(user.getLastLoginTime());
		// Entity 的 createdAt 在 VO 中暴露为 createTime，统一前端字段命名
		vo.setCreateTime(user.getCreatedAt());
		// 填充角色列表（与 list 接口保持一致，返回 RoleItem 对象）
		if (user.getRoles() != null) {
			vo.setRoles(user.getRoles().stream()
					.filter(r -> r.getId() != null)
					.map(r -> new UserVO.RoleItem(r.getId(), r.getName(), r.getCode()))
					.collect(Collectors.toList()));
		}
		return ResultVO.getSuccessVO("查询详情成功").setData(vo);
	}

	/** 新增用户 - 需要 system:user 权限 */
	@Operation(summary = "新增用户")
	@RequiresPermissions("system:user")
	@PostMapping("/add")
	public ResultVO add(@RequestBody User user) {
		userService.addUser(user);
		log.info("添加成功username={}", user.getUsername());
		return ResultVO.getSuccessVO("保存成功");
	}

	/** 删除用户 - 需要 system:user 权限 */
	@Operation(summary = "删除用户")
	@RequiresPermissions("system:user")
	@GetMapping("/delete")
	public ResultVO delete(Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		User user = userService.getById(id);
		if (user == null) {
			return ResultVO.getFailedVO("User not found");
		}
		userService.removeById(id);
		log.info("删除成功username={}", user.getUsername());
		return ResultVO.getSuccessVO("删除成功");
	}

	/** 修改用户 - 需要 system:user 权限 */
	@Operation(summary = "修改用户")
	@RequiresPermissions("system:user")
	@PostMapping("/update")
	public ResultVO update(@RequestBody User user) {
		if (user.getId() == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		User existingUser = userService.getById(user.getId());
		if (existingUser == null) {
			return ResultVO.getFailedVO("User not found");
		}
		userService.updateById(user);
		log.info("更新成功username={}", user.getUsername());
		return ResultVO.getSuccessVO("修改成功");
	}

	@Operation(summary = "刷新Token")
	@PostMapping("/refresh-token")
	public ResultVO refreshToken(@RequestBody Map<String, String> payload, HttpServletRequest request) {
		String refreshToken = payload.get("refreshToken");
		if (refreshToken == null || refreshToken.trim().isEmpty()) {
			return ResultVO.getFailedVO("Refresh token is required");
		}

		try {
			UserTokenPair refreshTokens = authManager.refreshTokens(refreshToken, request);
			return ResultVO.getSuccessVO().setData(refreshTokens);
		} catch (Exception e) {
			log.warn("Refresh token failed: {}", e.getMessage());
			return ResultVO.getFailedVO("Invalid or expired refresh token");
		}
	}

	// 登出（使 refresh token 失效）
	@Operation(summary = "用户登出")
	@PostMapping("/logout")
	public ResultVO logout(@RequestBody Map<String, String> payload) {
		String refreshToken = payload.get("refreshToken");
		if (refreshToken == null || refreshToken.trim().isEmpty()) {
			return ResultVO.getFailedVO("Refresh token is required for logout");
		}
		try {
			authManager.logout(refreshToken);
			return ResultVO.getSuccessVO("登出成功");
		} catch (Exception e) {
			log.warn("Logout failed: {}", e.getMessage());
			return ResultVO.getFailedVO("Logout failed");
		}
	}

	/** 分配用户角色（全量覆盖） - 需要 system:user 权限 */
	@Operation(summary = "分配用户角色")
	@RequiresPermissions("system:user")
	@PostMapping("/assignRoles")
	public ResultVO assignRoles(@RequestBody Map<String, Object> payload) {
		Object userIdObj = payload.get("userId");
		Object roleIdsObj = payload.get("roleIds");
		if (userIdObj == null) {
			return ResultVO.getFailedVO("userId 不能为空");
		}
		Long userId = Long.valueOf(userIdObj.toString());
		@SuppressWarnings("unchecked")
		List<Integer> roleIds = roleIdsObj != null ? ((List<Number>) roleIdsObj)
				.stream().map(Number::intValue).toList() : List.of();
		try {
			permissionManager.assignRoles(userId, roleIds);
			log.info("分配角色成功userId={}, roleIds={}", userId, roleIds);
			return ResultVO.getSuccessVO("分配角色成功");
		} catch (Exception e) {
			log.warn("分配角色失败: {}", e.getMessage());
			return ResultVO.getFailedVO(e.getMessage());
		}
	}

	/** 修改当前用户密码 - 需要登录 */
	@Operation(summary = "修改密码")
	@RequiresUser
	@PostMapping("/update-password")
	public ResultVO updatePassword(@CurrentUser User currentUser, @RequestBody Map<String, String> payload) {
		String oldPassword = payload.get("oldPassword");
		String newPassword = payload.get("newPassword");
		if (oldPassword == null || oldPassword.trim().isEmpty()) {
			return ResultVO.getFailedVO("旧密码不能为空");
		}
		if (newPassword == null || newPassword.trim().isEmpty()) {
			return ResultVO.getFailedVO("新密码不能为空");
		}
		try {
			// 1. Service 层只负责单实体的密码更新业务规则
			userService.updatePassword(currentUser.getUsername(), oldPassword, newPassword);
			// 2. 跨实体编排：撤销该用户所有 refresh token，强制重新登录（由 AuthManager 统一管理）
			authManager.revokeAllByUserId(currentUser.getId());
			log.info("修改密码成功username={}", currentUser.getUsername());
			return ResultVO.getSuccessVO("密码修改成功");
		} catch (Exception e) {
			log.warn("修改密码失败: {}", e.getMessage());
			return ResultVO.getFailedVO(e.getMessage());
		}
	}

	/** 查询当前用户的在线设备列表 */
	@Operation(summary = "查询在线设备")
	@RequiresUser
	@GetMapping("/online-devices")
	public ResultVO getOnlineDevices(@CurrentUser User currentUser, HttpServletRequest request) {
		// 解析当前请求的设备名称，用于标记"当前设备"
		String deviceName = authManager.getDeviceName(request);
		return ResultVO.getSuccessVO("查询成功").setData(authManager.getOnlineDevices(currentUser.getId(), deviceName));
	}

	/** 踢下线指定设备 */
	@Operation(summary = "踢下线设备")
	@RequiresUser
	@PostMapping("/kick-device")
	public ResultVO kickDevice(@CurrentUser User currentUser, @RequestBody Map<String, Long> params) {
		Long tokenId = params.get("tokenId");
		if (tokenId == null) {
			return ResultVO.getFailedVO("tokenId 不能为空");
		}
		try {
			authManager.kickDevice(currentUser.getId(), tokenId);
			log.info("踢下线成功userId={}, tokenId={}", currentUser.getId(), tokenId);
			return ResultVO.getSuccessVO("已踢下线");
		} catch (Exception e) {
			log.warn("踢下线失败: {}", e.getMessage());
			return ResultVO.getFailedVO(e.getMessage());
		}
	}

}
