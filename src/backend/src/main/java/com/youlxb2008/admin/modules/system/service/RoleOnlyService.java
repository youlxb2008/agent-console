package com.youlxb2008.admin.modules.system.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.modules.system.entity.RoleOnlyEntity;
import com.youlxb2008.admin.modules.system.entity.UserRolePermission;
import com.youlxb2008.admin.modules.system.mapper.RoleOnlyMapper;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;

@Service
public class RoleOnlyService extends ServiceImpl<RoleOnlyMapper, RoleOnlyEntity> {

    @Autowired
    private UserRolePermissionService rolePermissionService;

    /**
     * 分配角色菜单权限（全量覆盖）
     * 先删除该角色所有旧的权限关联，再批量插入新的关联记录
     *
     * @param roleId  角色ID
     * @param menuIds 菜单/权限ID列表
     */
    @DSTransactional
    public void assignMenus(Integer roleId, List<Integer> menuIds) {
        Assert.notNull(roleId, "角色ID不能为空");
        // 校验角色是否存在
        RoleOnlyEntity role = this.getById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        // 删除该角色所有旧的权限关联
        rolePermissionService.remove(
                Wrappers.<UserRolePermission>lambdaQuery().eq(UserRolePermission::getRoleId, roleId));
        // 批量插入新的权限关联
        if (CollUtil.isNotEmpty(menuIds)) {
            for (Integer menuId : menuIds) {
                UserRolePermission rp = new UserRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(menuId);
                rolePermissionService.save(rp);
            }
        }
    }
}
