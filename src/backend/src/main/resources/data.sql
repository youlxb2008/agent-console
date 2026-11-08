-- ============================================================
-- 管理端 种子数据
-- 兼容 H2（MODE=MySQL）和 MySQL 5.7+
--
-- 幂等策略：每条 INSERT 用 WHERE NOT EXISTS 守卫，
-- 表中已有数据时跳过，不覆盖用户修改过的值。
-- 全新数据库启动时写入全部种子数据；已有数据库重启无副作用。
-- ============================================================

-- ------------------------------------------------------------
-- 角色
-- ------------------------------------------------------------
INSERT INTO `t_role` (`id`, `name`, `code`, `sort`, `status`, `remark`)
SELECT 1, '超级管理员', 'super_admin', 0, 0, '拥有全部系统权限'
WHERE NOT EXISTS (SELECT 1 FROM `t_role` WHERE `id` = 1);

INSERT INTO `t_role` (`id`, `name`, `code`, `sort`, `status`, `remark`)
SELECT 2, '普通管理员', 'normal_admin', 1, 0, '可进行日常管理操作'
WHERE NOT EXISTS (SELECT 1 FROM `t_role` WHERE `id` = 2);

-- ------------------------------------------------------------
-- 用户
-- admin 默认密码：123456（BCrypt 哈希）
-- manager 默认密码：123456（BCrypt 哈希）
-- ------------------------------------------------------------
INSERT INTO `t_user` (`id`, `name`, `username`, `password`, `user_type`, `email`, `phone`, `enabled`, `must_change_password`)
SELECT 1, '超级管理员', 'admin', '$2a$10$9N5odH4D6Kq5qZndS8VdHe/Hnx1CSzfLj.w/qyF4PmOLVVHMSJE9W', 0, 'admin@example.com', '13500000001', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_user` WHERE `id` = 1);

INSERT INTO `t_user` (`id`, `name`, `username`, `password`, `user_type`, `email`, `phone`, `enabled`, `must_change_password`)
SELECT 2, '普通管理员', 'manager', '$2a$10$QL.hltPHidK.a9duelx/P.idTNdlF2fZdpEgSzk2bu5iwCtDbGFwq', 1, 'manager@example.com', '13800000001', 0, 0
WHERE NOT EXISTS (SELECT 1 FROM `t_user` WHERE `id` = 2);

-- ------------------------------------------------------------
-- 用户-角色关联
-- ------------------------------------------------------------
INSERT INTO `t_user_role` (`id`, `role_id`, `user_id`)
SELECT 1, 1, 1 WHERE NOT EXISTS (SELECT 1 FROM `t_user_role` WHERE `id` = 1);

INSERT INTO `t_user_role` (`id`, `role_id`, `user_id`)
SELECT 2, 2, 2 WHERE NOT EXISTS (SELECT 1 FROM `t_user_role` WHERE `id` = 2);

-- ------------------------------------------------------------
-- 权限 / 菜单
-- 一级目录
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 1, '系统管理', 'system', 1, 0, 1, '/system', NULL, 'ph:gear', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 1);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 2, '通知管理', 'notice', 1, 0, 1, '/notice', NULL, 'ph:bell', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 2);

-- 二级菜单（系统管理下）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 10, '用户管理', 'system:user', 2, 1, 2, '/system/user', 'system/user/index', 'ph:user', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 10);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 11, '角色管理', 'system:role', 2, 1, 2, '/system/role', 'system/role/index', 'ph:users', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 11);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 12, '菜单管理', 'system:menu', 2, 1, 2, '/system/menu', 'system/menu/index', 'ph:list', 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 12);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 14, '定时任务', 'system:cron', 2, 1, 2, '/system/cron', 'system/cron/index', 'ph:clock', 5, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 14);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 15, '站点配置', 'system:site', 2, 1, 2, '/system/site', 'system/site/index', 'ph:sliders', 6, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 15);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 16, '系统工具箱', 'system:tools', 2, 1, 2, '/system/tools', 'system/tools/index', 'ph:wrench', 7, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 16);

-- 系统配置管理菜单（t_option 表 CRUD）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 17, '系统配置', 'system:option', 2, 1, 2, '/system/option', 'system/option/index', 'ph:gear-six', 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 17);

-- 二级菜单（通知管理下）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 20, '消息通知', 'notice:view', 2, 2, 2, '/system/notice', 'system/notice/index', 'ph:envelope', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 20);

-- 三级按钮（用户管理下）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 100, '用户新增', 'system:user:add', 3, 10, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 100);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 101, '用户编辑', 'system:user:edit', 3, 10, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 101);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 102, '用户删除', 'system:user:delete', 3, 10, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 102);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 103, '分配角色', 'system:user:assign', 3, 10, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 103);

-- 三级按钮（角色管理下）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 110, '角色新增', 'system:role:add', 3, 11, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 110);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 111, '角色编辑', 'system:role:edit', 3, 11, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 111);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 112, '角色删除', 'system:role:delete', 3, 11, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 112);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 113, '分配菜单', 'system:role:assign', 3, 11, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 113);

-- 三级按钮（菜单管理下）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 120, '菜单新增', 'system:menu:add', 3, 12, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 120);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 121, '菜单编辑', 'system:menu:edit', 3, 12, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 121);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 122, '菜单删除', 'system:menu:delete', 3, 12, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 122);

-- 三级按钮（定时任务下）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 140, '任务新增', 'system:cron:add', 3, 14, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 140);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 141, '任务编辑', 'system:cron:edit', 3, 14, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 141);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 142, '任务删除', 'system:cron:delete', 3, 14, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 142);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 143, '手动执行', 'system:cron:trigger', 3, 14, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 143);

-- 三级按钮（系统配置下）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 170, '配置新增', 'system:option:add', 3, 17, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 170);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 171, '配置编辑', 'system:option:edit', 3, 17, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 171);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 172, '配置删除', 'system:option:delete', 3, 17, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 172);

-- 三级按钮（消息通知下）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 200, '通知新增', 'notice:create', 3, 20, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 200);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 201, '通知编辑', 'notice:update', 3, 20, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 201);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 202, '通知删除', 'notice:delete', 3, 20, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 202);

-- ------------------------------------------------------------
-- 角色-权限关联
-- 超级管理员（role_id=1）：自动同步所有权限（首次写入，之后不再追加新权限）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`)
SELECT 1, `id` FROM `t_permission`
WHERE NOT EXISTS (
    SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1
);

-- 普通管理员（role_id=2）：部分权限
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 2 AND `permission_id` = 1);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 2, 10
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 2 AND `permission_id` = 10);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 2, 11
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 2 AND `permission_id` = 11);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 2, 13
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 2 AND `permission_id` = 13);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 2, 15
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 2 AND `permission_id` = 15);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 2, 100
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 2 AND `permission_id` = 100);

-- ------------------------------------------------------------
-- 站点配置（key-value，已存在不覆盖）
-- ------------------------------------------------------------
INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'site_title', 'Agent Console'
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'site_title');

-- 门户质感方案（aurora 柔光 / relief 浮雕 / grid 网格），管理平台站点设置可改
INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'portal_texture', 'aurora'
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'portal_texture');

INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'site_subtitle', '管理后台'
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'site_subtitle');

INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'site_logo', 'ph:shield-check'
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'site_logo');

INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'site_copyright', ''
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'site_copyright');

INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'site_icp', ''
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'site_icp');

INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'site_login_bg', ''
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'site_login_bg');

INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'site_layout_width', 'default'
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'site_layout_width');

-- 主题色：靛蓝紫（默认），可选：indigo / emerald / rose / amber / slate
INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'site_theme', 'indigo'
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'site_theme');

-- 布局风格：dark（暗色 header/sidebar）/ light（明色），默认 dark
INSERT INTO `t_option` (`option_key`, `option_value`)
SELECT 'site_layout_mode', 'dark'
WHERE NOT EXISTS (SELECT 1 FROM `t_option` WHERE `option_key` = 'site_layout_mode');

-- ------------------------------------------------------------
-- 消息通知示例
-- ------------------------------------------------------------
INSERT INTO `t_notice` (`title`, `content`, `type`, `status`, `publisher`, `remark`)
SELECT '欢迎使用 管理端 管理系统', '<p>系统已部署成功，请及时修改默认密码。</p>', 1, 1, 'admin', '系统初始化'
WHERE NOT EXISTS (SELECT 1 FROM `t_notice` WHERE `title` = '欢迎使用 管理端 管理系统');

INSERT INTO `t_notice` (`title`, `content`, `type`, `status`, `publisher`, `remark`)
SELECT 'v1.0.0 版本发布', '<p>新增消息通知模块，优化菜单权限管理。</p>', 2, 1, 'admin', NULL
WHERE NOT EXISTS (SELECT 1 FROM `t_notice` WHERE `title` = 'v1.0.0 版本发布');

INSERT INTO `t_notice` (`title`, `content`, `type`, `status`, `publisher`, `remark`)
SELECT '系统维护通知（草稿）', '<p>计划于本周日凌晨 2:00-4:00 进行系统维护。</p>', 3, 0, 'admin', NULL
WHERE NOT EXISTS (SELECT 1 FROM `t_notice` WHERE `title` = '系统维护通知（草稿）');

-- ------------------------------------------------------------
-- 定时任务示例
-- ------------------------------------------------------------
INSERT INTO `t_cron_job` (`name`, `cron_expr`, `handler_class`, `params`, `status`)
SELECT '示例问候任务', '0 0/5 * * * ?', 'demoHello', '{"name":"管理端"}', 0
WHERE NOT EXISTS (SELECT 1 FROM `t_cron_job` WHERE `handler_class` = 'demoHello');

INSERT INTO `t_cron_job` (`name`, `cron_expr`, `handler_class`, `params`, `status`)
SELECT '清理过期日志', '0 0 2 * * ?', 'cleanExpiredLogs', '{"days":30}', 0
WHERE NOT EXISTS (SELECT 1 FROM `t_cron_job` WHERE `handler_class` = 'cleanExpiredLogs');
