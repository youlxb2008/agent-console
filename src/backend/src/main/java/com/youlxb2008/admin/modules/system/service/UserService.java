package com.youlxb2008.admin.modules.system.service;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.modules.system.mapper.UserMapper;

import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.BCrypt;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户 Service
 * 专注单实体 CRUD 与简单业务规则
 * 跨域编排逻辑已迁移至 AuthManager / PermissionManager
 */
@Slf4j
@Service
public class UserService extends ServiceImpl<UserMapper, User> {
	@Resource
	private UserMapper userMapper;

	/**
	 * 根据用户名查询用户
	 */
	public User getByUsername(String username) {
		return super.getOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username), true);
	}

	/**
	 * 新增用户（重名校验 + BCrypt 加密密码）
	 */
	public void addUser(User user) {
		Assert.notNull(user, "入参异常");
		Assert.notNull(user.getUsername(), "入参异常");
		Assert.notNull(user.getPassword(), "入参异常");
		Long selectCount = userMapper
				.selectCount(Wrappers.<User>lambdaQuery().eq(User::getUsername, user.getUsername()));
		if (selectCount > 0) {
			throw new BusinessException("已存在该用户");
		}
		user.setPassword(BCrypt.hashpw(user.getPassword()));
		userMapper.insert(user);
	}

	/**
	 * 修改当前用户密码
	 * 仅负责单实体的密码更新业务规则：校验旧密码、加密新密码、清除强制改密标记。
	 * 注意：撤销该用户已有 refresh token 的跨实体编排逻辑由上层 AuthManager 负责，
	 * 不在 Service 层处理（避免直接依赖 UserRefreshTokenMapper）。
	 *
	 * @param username    当前登录用户名
	 * @param oldPassword 旧密码（明文）
	 * @param newPassword 新密码（明文）
	 */
	public void updatePassword(String username, String oldPassword, String newPassword) {
		Assert.notBlank(username, "用户名不能为空");
		Assert.notBlank(oldPassword, "旧密码不能为空");
		Assert.notBlank(newPassword, "新密码不能为空");
		User user = super.getOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username), true);
		if (user == null) {
			throw new BusinessException("用户不存在");
		}
		if (!BCrypt.checkpw(oldPassword, user.getPassword())) {
			throw new BusinessException("旧密码错误");
		}
		if (oldPassword.equals(newPassword)) {
			throw new BusinessException("新密码不能与旧密码相同");
		}
		user.setPassword(BCrypt.hashpw(newPassword));
		// 修改密码成功后清除强制改密标记
		user.setMustChangePassword(false);
		userMapper.updateById(user);
	}
}
