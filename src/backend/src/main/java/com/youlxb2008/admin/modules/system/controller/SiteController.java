package com.youlxb2008.admin.modules.system.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.youlxb2008.admin.modules.system.service.SiteOptionService;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 站点配置 Controller
 * 提供站点全局配置的查询与批量保存
 * - /list 公开接口，无需登录（登录页需要读取站点标题/主题/背景图等）
 * - /save 需要登录
 */
@RestController
@RequestMapping(path = "/system/site")
@Slf4j
@Tag(name = "站点配置", description = "系统配置项读写")
public class SiteController {

	@Autowired
	private SiteOptionService optionService;

	/**
	 * 获取全部站点配置项（公开接口，无需登录）
	 * 登录页需要读取站点标题、主题色、背景图等配置
	 * 
	 * @return key-value 形式的配置 Map
	 */
	@Operation(summary = "获取全部站点配置项（公开）")
	@GetMapping("/list")
	public ResultVO list() {
		Map<String, String> options = optionService.getAllOptions();
		return ResultVO.getSuccessVO("查询成功").setData(options);
	}

	/**
	 * 批量保存站点配置项
	 * 
	 * @param options 配置项 Map（key 为 optionKey，value 为 optionValue）
	 * @return 操作结果
	 */
	@Operation(summary = "批量保存站点配置项")
	@RequiresUser
	@PostMapping("/save")
	public ResultVO save(@RequestBody Map<String, String> options) {
		optionService.saveOptions(options);
		log.info("站点配置批量保存成功");
		return ResultVO.getSuccessVO("保存成功");
	}
}
