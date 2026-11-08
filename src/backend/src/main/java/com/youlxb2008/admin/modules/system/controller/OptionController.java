package com.youlxb2008.admin.modules.system.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.modules.system.entity.SiteOption;
import com.youlxb2008.admin.modules.system.service.SiteOptionService;
import com.youlxb2008.admin.modules.system.vo.SiteOptionVO;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.page.PageSearch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 系统配置管理 Controller
 * <p>
 * 提供 t_option 表的完整 CRUD 接口，
 * 与 SiteController（/system/site，面向前端站点的批量读写）互不干扰。
 */
@RestController
@RequestMapping(path = "/system/option")
@Slf4j
@Tag(name = "系统配置", description = "t_option 表 CRUD 管理")
public class OptionController {

	@Autowired
	private SiteOptionService optionService;

	/**
	 * 分页查询配置项列表
	 * 支持按 optionKey 模糊搜索
	 *
	 * @param option 查询条件（optionKey 用于模糊匹配）
	 * @param page   分页参数
	 * @return 分页结果
	 */
	@Operation(summary = "分页查询配置项列表")
	@RequiresPermissions("system:option")
	@PostMapping("/list")
	public ResultVO list(SiteOption option, PageSearch page) {
		LambdaQueryWrapper<SiteOption> wrapper = new LambdaQueryWrapper<>();
		// 按 optionKey 模糊查询
		if (option.getOptionKey() != null && !option.getOptionKey().isEmpty()) {
			wrapper.like(SiteOption::getOptionKey, option.getOptionKey());
		}
		// 按 id 升序排列，保持稳定顺序
		wrapper.orderByAsc(SiteOption::getId);
		IPage<SiteOption> pageData = optionService.page(page.toDBPage(), wrapper);
		// Entity → VO 转换
		IPage<SiteOptionVO> voPage = pageData.convert(this::convertToVO);
		return ResultVO.getSuccessVO("查询列表成功").setData(voPage);
	}

	/**
	 * 根据ID查询配置项详情
	 *
	 * @param id 配置项ID
	 * @return 配置项详情
	 */
	@Operation(summary = "查询配置项详情")
	@RequiresPermissions("system:option")
	@GetMapping("/getById")
	public ResultVO getById(Integer id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		SiteOption option = optionService.getById(id);
		if (option == null) {
			return ResultVO.getFailedVO("配置项不存在");
		}
		return ResultVO.getSuccessVO("查询详情成功").setData(convertToVO(option));
	}

	/**
	 * 新增配置项
	 * optionKey 必须唯一，重复则返回失败
	 *
	 * @param option 配置项实体
	 * @return 操作结果
	 */
	@Operation(summary = "新增配置项")
	@RequiresPermissions("system:option")
	@PostMapping("/add")
	public ResultVO add(@RequestBody SiteOption option) {
		try {
			// 检查 optionKey 是否已存在
			if (option.getOptionKey() != null && !option.getOptionKey().isEmpty()) {
				long count = optionService.count(
					new LambdaQueryWrapper<SiteOption>().eq(SiteOption::getOptionKey, option.getOptionKey())
				);
				if (count > 0) {
					return ResultVO.getFailedVO("配置键「" + option.getOptionKey() + "」已存在");
				}
			}
			optionService.save(option);
			return ResultVO.getSuccessVO("新增成功");
		} catch (Exception e) {
			log.error("新增配置项失败", e);
			return ResultVO.getFailedVO("新增失败");
		}
	}

	/**
	 * 修改配置项
	 * 不允许修改 optionKey（唯一键），只修改 optionValue
	 *
	 * @param option 配置项实体（必须包含 id）
	 * @return 操作结果
	 */
	@Operation(summary = "修改配置项")
	@RequiresPermissions("system:option")
	@PostMapping("/update")
	public ResultVO update(@RequestBody SiteOption option) {
		try {
			if (option.getId() == null) {
				return ResultVO.getFailedVO("ID is required");
			}
			optionService.updateById(option);
			return ResultVO.getSuccessVO("修改成功");
		} catch (Exception e) {
			log.error("修改配置项失败", e);
			return ResultVO.getFailedVO("修改失败");
		}
	}

	/**
	 * 根据ID删除配置项
	 *
	 * @param id 配置项ID
	 * @return 操作结果
	 */
	@Operation(summary = "删除配置项")
	@RequiresPermissions("system:option")
	@GetMapping("/delete")
	public ResultVO delete(Integer id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		try {
			optionService.removeById(id);
			return ResultVO.getSuccessVO("删除成功");
		} catch (Exception e) {
			log.error("删除配置项失败", e);
			return ResultVO.getFailedVO("删除失败");
		}
	}

	/**
	 * SiteOption 实体转视图对象
	 * @param option 配置项实体
	 * @return 配置项 VO（出参）
	 */
	private SiteOptionVO convertToVO(SiteOption option) {
		SiteOptionVO vo = new SiteOptionVO();
		vo.setId(option.getId());
		vo.setOptionKey(option.getOptionKey());
		vo.setOptionValue(option.getOptionValue());
		return vo;
	}
}
