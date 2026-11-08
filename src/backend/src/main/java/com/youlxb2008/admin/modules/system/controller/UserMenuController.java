package com.youlxb2008.admin.modules.system.controller;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.modules.system.entity.UserPermission;
import com.youlxb2008.admin.modules.system.manager.PermissionManager;
import com.youlxb2008.admin.modules.system.service.UserPermissionService;
import com.youlxb2008.admin.modules.system.vo.UserPermissionVO;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.CurrentUser;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/system/menu")
@Slf4j
@Tag(name = "菜单管理", description = "菜单/权限树CRUD")
public class UserMenuController {

    @Autowired
    private UserPermissionService permissionService;

    @Autowired
    private PermissionManager permissionManager;

    /**
     * 获取当前登录用户的可见菜单树
     * <p>
     * 专门用于登录后渲染侧边栏 / 注册动态路由。与 /tree 区别：
     * <ul>
     *   <li>/my：只返回当前用户有权限的、type≤2（目录/菜单）、启用的节点，任意登录用户可访问</li>
     *   <li>/tree：返回全量权限树（含按钮），需要 system:menu 权限，给菜单管理页用</li>
     * </ul>
     * </p>
     */
    @Operation(summary = "获取当前用户菜单树（侧边栏渲染用）")
    @RequiresUser
    @GetMapping("/my")
    public ResultVO getMyMenuTree(@CurrentUser User currentUser) {
        try {
            // 1. 拿当前用户的扁平权限列表（超管返回全部，普通用户走角色关联）
            List<UserPermission> permissions = permissionManager
                    .getPermissionsByUsername(currentUser.getUsername());
            // 2. 过滤：type≤2（目录/菜单，排除按钮）+ 启用状态 + 按 sortOrder 升序
            List<UserPermission> visible = permissions.stream()
                    .filter(p -> p.getType() != null && p.getType() <= 2)
                    .filter(p -> p.getStatus() == null || p.getStatus() == 1)
                    .sorted((a, b) -> {
                        Integer sa = a.getSortOrder();
                        Integer sb = b.getSortOrder();
                        return Integer.compare(sa == null ? 0 : sa, sb == null ? 0 : sb);
                    })
                    .collect(Collectors.toList());
            // 3. 构建树（根节点 parentId=0 或 null）
            List<UserPermission> tree = permissionService.buildTree(visible, 0);
            // 4. Entity → VO 递归转换
            List<UserPermissionVO> voTree = tree.stream().map(this::convertToVO).collect(Collectors.toList());
            return ResultVO.getSuccessVO().setData(voTree);
        } catch (Exception e) {
            log.error("获取当前用户菜单树失败", e);
            return ResultVO.getFailedVO("获取菜单失败");
        }
    }

    /** 获取菜单树 - 需要 system:menu 权限 */
    @Operation(summary = "获取菜单树")
    @RequiresPermissions("system:menu")
    @GetMapping("/tree")
    public ResultVO getMenuTree() {
        try {
            List<UserPermission> tree = permissionService.getPermissionTree();
            // Entity → VO 递归转换（包含 children）
            List<UserPermissionVO> voTree = tree.stream().map(this::convertToVO).collect(Collectors.toList());
            return ResultVO.getSuccessVO().setData(voTree);
        } catch (Exception e) {
            log.error("获取菜单树失败", e);
            return ResultVO.getFailedVO("获取菜单树失败");
        }
    }

    /** 获取菜单列表 - 需要 system:menu 权限 */
    @Operation(summary = "获取菜单列表")
    @RequiresPermissions("system:menu")
    @GetMapping("/list")
    public ResultVO getMenuList() {
        try {
            List<UserPermission> list = permissionService.list();
            // Entity → VO 转换（扁平列表，仍递归处理 children 以保证一致性）
            List<UserPermissionVO> voList = list.stream().map(this::convertToVO).collect(Collectors.toList());
            return ResultVO.getSuccessVO().setData(voList);
        } catch (Exception e) {
            log.error("获取菜单列表失败", e);
            return ResultVO.getFailedVO("获取菜单列表失败");
        }
    }

    /** 新增菜单 - 需要 system:menu 权限 */
    @Operation(summary = "新增菜单")
    @RequiresPermissions("system:menu")
    @PostMapping
    public ResultVO addMenu(@Valid @RequestBody UserPermission menu) {
        try {
            boolean success = permissionService.addPermission(menu);
            if (success) {
                return ResultVO.getSuccessVO("新增菜单成功");
            } else {
                return ResultVO.getFailedVO("新增菜单失败");
            }
        } catch (IllegalArgumentException e) {
            return ResultVO.getFailedVO(e.getMessage());
        } catch (Exception e) {
            log.error("新增菜单异常", e);
            return ResultVO.getFailedVO("系统异常，请稍后重试");
        }
    }

    /** 修改菜单 - 需要 system:menu 权限 */
    @Operation(summary = "修改菜单")
    @RequiresPermissions("system:menu")
    @PutMapping
    public ResultVO updateMenu(@Valid @RequestBody UserPermission menu) {
        if (menu.getId() == null) {
            return ResultVO.getFailedVO("菜单ID不能为空");
        }
        try {
            boolean success = permissionService.updatePermission(menu);
            if (success) {
                return ResultVO.getSuccessVO("更新菜单成功");
            } else {
                return ResultVO.getFailedVO("更新菜单失败");
            }
        } catch (IllegalArgumentException e) {
            return ResultVO.getFailedVO(e.getMessage());
        } catch (Exception e) {
            log.error("更新菜单异常", e);
            return ResultVO.getFailedVO("系统异常，请稍后重试");
        }
    }

    /** 删除菜单 - 需要 system:menu 权限 */
    @Operation(summary = "删除菜单")
    @RequiresPermissions("system:menu")
    @DeleteMapping("/{id}")
    public ResultVO deleteMenu(@PathVariable Integer id) {
        if (id == null || id <= 0) {
            return ResultVO.getFailedVO("无效的菜单ID");
        }
        try {
            boolean success = permissionService.removePermission(id);
            if (success) {
                return ResultVO.getSuccessVO("删除菜单成功");
            } else {
                return ResultVO.getFailedVO("删除失败");
            }
        } catch (IllegalStateException e) {
            return ResultVO.getFailedVO(e.getMessage()); // 如"存在子权限，无法删除"
        } catch (Exception e) {
            log.error("删除菜单异常", e);
            return ResultVO.getFailedVO("系统异常，请稍后重试");
        }
    }

    /** 启用/禁用菜单 - 需要 system:menu 权限 */
    @Operation(summary = "启用/禁用菜单")
    @RequiresPermissions("system:menu")
    @PostMapping("/toggleStatus")
    public ResultVO toggleMenuStatus(@RequestParam Integer id, @RequestParam Integer status) {
        if (id == null || status == null) {
            return ResultVO.getFailedVO("参数缺失：id 和 status 不能为空");
        }
        if (status != 0 && status != 1) {
            return ResultVO.getFailedVO("状态值只能为 0（禁用）或 1（启用）");
        }
        try {
            boolean success = permissionService.toggleStatus(id, status);
            if (success) {
                String msg = status == 1 ? "启用成功" : "禁用成功";
                return ResultVO.getSuccessVO(msg);
            } else {
                return ResultVO.getFailedVO("操作失败");
            }
        } catch (Exception e) {
            log.error("切换菜单状态异常", e);
            return ResultVO.getFailedVO("系统异常，请稍后重试");
        }
    }

    /** 获取菜单详情 - 需要 system:menu 权限 */
    @Operation(summary = "获取菜单详情")
    @RequiresPermissions("system:menu")
    @GetMapping("/{id}")
    public ResultVO getMenuDetail(@PathVariable Integer id) {
        if (id == null) {
            return ResultVO.getFailedVO("ID不能为空");
        }
        UserPermission menu = permissionService.getById(id);
        if (menu == null) {
            return ResultVO.getFailedVO("菜单不存在");
        }
        // Entity → VO 转换后返回
        return ResultVO.getSuccessVO().setData(convertToVO(menu));
    }

    /**
     * 菜单/权限实体递归转视图对象
     * <p>
     * 字段别名映射：
     * - perms ← permissionKey
     * - path ← routePath
     * - sort ← sortOrder
     * - createTime ← createdAt
     * @param p 权限实体
     * @return 权限 VO（包含递归处理后的 children）
     */
    private UserPermissionVO convertToVO(UserPermission p) {
        UserPermissionVO vo = new UserPermissionVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        // 前端字段别名：perms 对应 permissionKey
        vo.setPerms(p.getPermissionKey());
        vo.setType(p.getType());
        vo.setParentId(p.getParentId());
        vo.setLevel(p.getLevel());
        // 前端字段别名：path 对应 routePath
        vo.setPath(p.getRoutePath());
        vo.setRoutePath(p.getRoutePath());
        vo.setComponent(p.getComponent());
        vo.setIcon(p.getIcon());
        // 前端字段别名：sort 对应 sortOrder
        vo.setSort(p.getSortOrder());
        vo.setSortOrder(p.getSortOrder());
        vo.setStatus(p.getStatus());
        vo.setDescription(p.getDescription());
        // 前端字段别名：createTime 对应 createdAt
        vo.setCreateTime(p.getCreatedAt());
        // 递归处理子权限
        if (p.getChildren() != null && !p.getChildren().isEmpty()) {
            vo.setChildren(p.getChildren().stream()
                    .map(this::convertToVO)
                    .collect(Collectors.toList()));
        }
        return vo;
    }
}
