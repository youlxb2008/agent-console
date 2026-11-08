package com.youlxb2008.admin.modules.system.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.modules.system.entity.RoleOnlyEntity;
import com.youlxb2008.admin.modules.system.entity.UserRolePermission;
import com.youlxb2008.admin.modules.system.service.RoleOnlyService;
import com.youlxb2008.admin.modules.system.service.UserRolePermissionService;
import com.youlxb2008.admin.modules.system.vo.RoleVO;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.page.PageSearch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping(path = "/system/role")
@Slf4j
@Tag(name = "角色管理", description = "角色CRUD/菜单权限分配")
public class UserRoleController {
	@Autowired
	private RoleOnlyService roleService;

	@Autowired
	private UserRolePermissionService rolePermissionService;

	/** 查看角色列表 - 需要 system:role 权限 */
	@Operation(summary = "分页查询角色列表")
	@RequiresPermissions("system:role")
	@PostMapping("/list")
	public ResultVO list(RoleOnlyEntity role, PageSearch page) {
		QueryWrapper<RoleOnlyEntity> queryWrapper = new QueryWrapper<RoleOnlyEntity>(role);
		IPage<RoleOnlyEntity> pageData = roleService.page(page.toDBPage(), queryWrapper);
		// Entity → VO 转换，避免 Entity 出栈泄露内部字段
		IPage<RoleVO> voPage = pageData.convert(this::convertToVO);
		return ResultVO.getSuccessVO("查询列表成功").setData(voPage);
	}

	/** 查看角色详情 - 需要 system:role 权限 */
	@Operation(summary = "查询角色详情")
	@RequiresPermissions("system:role")
	@GetMapping("/getById")
	public ResultVO getById(Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		RoleOnlyEntity role = roleService.getById(id);
		if (role == null) {
			return ResultVO.getFailedVO("RoleOnlyEntity not found");
		}
		// 查询该角色已分配的菜单/权限 ID 列表（供分配菜单弹窗回显）
		RoleVO vo = convertToVO(role);
		List<Integer> menuIds = rolePermissionService
				.list(com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserRolePermission>lambdaQuery()
						.eq(UserRolePermission::getRoleId, id))
				.stream()
				.map(UserRolePermission::getPermissionId)
				.toList();
		vo.setMenuIds(menuIds);
		return ResultVO.getSuccessVO("查询详情成功").setData(vo);
	}

	/**
	 * 角色实体转视图对象
	 * @param role 角色实体
	 * @return 角色 VO（出参）
	 */
	private RoleVO convertToVO(RoleOnlyEntity role) {
		RoleVO vo = new RoleVO();
		vo.setId(role.getId());
		vo.setName(role.getName());
		vo.setCode(role.getCode());
		vo.setSort(role.getSort());
		vo.setStatus(role.getStatus());
		vo.setRemark(role.getRemark());
		vo.setCreateTime(role.getCreatedAt());
		return vo;
	}

	/** 新增角色 - 需要 system:role 权限 */
	@Operation(summary = "新增角色")
	@RequiresPermissions("system:role")
	@PostMapping("/add")
	public ResultVO add(@RequestBody RoleOnlyEntity role) {
		roleService.save(role);
		log.info("添加成功role={}", role);
		return ResultVO.getSuccessVO("保存成功");
	}

	/** 删除角色 - 需要 system:role 权限 */
	@Operation(summary = "删除角色")
	@RequiresPermissions("system:role")
	@GetMapping("/delete")
	public ResultVO delete(Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		RoleOnlyEntity role = roleService.getById(id);
		if (role == null) {
			return ResultVO.getFailedVO("RoleOnlyEntity not found");
		}
		roleService.removeById(id);
		log.info("删除成功rolename={}", role.getName());
		return ResultVO.getSuccessVO("删除成功");
	}

	/** 修改角色 - 需要 system:role 权限 */
	@Operation(summary = "修改角色")
	@RequiresPermissions("system:role")
	@PostMapping("/update")
	public ResultVO update(@RequestBody RoleOnlyEntity role) {
		if (role.getId() == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		RoleOnlyEntity existingRole = roleService.getById(role.getId());
		if (existingRole == null) {
			return ResultVO.getFailedVO("RoleOnlyEntity not found");
		}
		roleService.updateById(role);
		log.info("更新成功rolename={}", role.getName());
		return ResultVO.getSuccessVO("修改成功");
	}

	/** 分配角色菜单权限（全量覆盖） - 需要 system:role 权限 */
	@Operation(summary = "分配角色菜单权限")
	@RequiresPermissions("system:role")
	@PostMapping("/assignMenus")
	public ResultVO assignMenus(@RequestBody Map<String, Object> payload) {
		Object roleIdObj = payload.get("roleId");
		Object menuIdsObj = payload.get("menuIds");
		if (roleIdObj == null) {
			return ResultVO.getFailedVO("roleId 不能为空");
		}
		Integer roleId = Integer.valueOf(roleIdObj.toString());
		// menuIds 可能为空列表（表示清除所有权限）
		@SuppressWarnings("unchecked")
		List<Integer> menuIds = menuIdsObj != null ? ((List<Number>) menuIdsObj)
				.stream().map(Number::intValue).toList() : List.of();
		try {
			roleService.assignMenus(roleId, menuIds);
			log.info("分配菜单权限成功roleId={}, menuIds={}", roleId, menuIds);
			return ResultVO.getSuccessVO("分配菜单权限成功");
		} catch (Exception e) {
			log.warn("分配菜单权限失败: {}", e.getMessage());
			return ResultVO.getFailedVO(e.getMessage());
		}
	}
}
