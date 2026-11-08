package com.youlxb2008.admin.modules.system.manager;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.modules.system.service.RoleOnlyService;
import com.youlxb2008.admin.modules.system.service.UserPermissionService;
import com.youlxb2008.admin.modules.system.service.UserService;

import lombok.extern.slf4j.Slf4j;

/**
 * 首页统计聚合 Manager
 * 汇总系统核心业务数据（用户数、角色数、菜单/权限数），供首页展示
 */
@Slf4j
@Component
public class DashboardManager {

	@Autowired
	private UserService userService;

	@Autowired
	private RoleOnlyService roleService;

	@Autowired
	private UserPermissionService permissionService;

	/**
	 * 获取首页统计数据
	 * 分别统计用户、角色、菜单/权限三张表的记录总数
	 */
	public Map<String, Object> getStats() {
		Map<String, Object> stats = new HashMap<>();
		long userCount = userService.count();
		long roleCount = roleService.count();
		long menuCount = permissionService.count();

		stats.put("userCount", userCount);
		stats.put("roleCount", roleCount);
		stats.put("menuCount", menuCount);
		log.info("Dashboard统计数据: userCount={}, roleCount={}, menuCount={}", userCount, roleCount, menuCount);
		return stats;
	}
}
