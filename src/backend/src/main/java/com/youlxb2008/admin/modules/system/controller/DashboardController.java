package com.youlxb2008.admin.modules.system.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.youlxb2008.admin.modules.system.manager.DashboardManager;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 仪表盘统计 Controller
 * 提供首页统计数据接口，登录用户即可访问
 */
@RestController
@RequestMapping(path = "/system/dashboard")
@Slf4j
@Tag(name = "仪表盘", description = "首页统计数据")
public class DashboardController {

	@Autowired
	private DashboardManager dashboardManager;

	/**
	 * 获取首页统计数据（用户数、角色数、菜单数）
	 */
	@Operation(summary = "获取首页统计数据")
	@RequiresUser
	@GetMapping("/stats")
	public ResultVO stats() {
		Map<String, Object> stats = dashboardManager.getStats();
		return ResultVO.getSuccessVO("查询成功").setData(stats);
	}
}
