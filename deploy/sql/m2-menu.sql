-- ============================================================
-- M2.0 笔记 v2 菜单 SQL（在目标 MySQL 5.7 执行）
-- 目标库：agent-console
--
-- 说明（沿 m13-menu.sql 写法）：
--   1. 3040 菜单「笔记审核」→「笔记管理」（name 已存在则 UPDATE，全新库走 INSERT 兜底）
--   2. 3131 按钮：权限码 agent:note:review 废弃 → agent:note:delete「笔记删除」
--   3. 超级管理员（role_id=1）关联 3131 幂等补齐（WHERE NOT EXISTS 守卫）
--   4. 其余权限/菜单（3000/3010-3030/3100-3120）不变，无需重复 INSERT
-- ============================================================

-- ------------------------------------------------------------
-- 3040 菜单改名：笔记审核 → 笔记管理
-- 已存在行：只改 name（icon ph:note-pencil / component 保留）
-- ------------------------------------------------------------
UPDATE `t_permission`
SET `name` = '笔记管理'
WHERE `id` = 3040 AND `name` = '笔记审核';

-- 全新库兜底：3040 不存在则 INSERT（v3 目标态：迁至 3050 下，组件 system/note/index）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3040, '笔记列表', 'agent:note', 2, 3050, 2, '/note/list', 'system/note/index', 'ph:note-pencil', 1, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3040);

-- ------------------------------------------------------------
-- 3131 按钮：agent:note:review（废弃）→ agent:note:delete（笔记删除）
-- 已存在行：只改 name + permission_key（type/parent 不变）
-- ------------------------------------------------------------
UPDATE `t_permission`
SET `name` = '笔记删除', `permission_key` = 'agent:note:delete'
WHERE `id` = 3131 AND `permission_key` = 'agent:note:review';

-- 全新库兜底：3131 不存在则 INSERT 新值
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3131, '笔记删除', 'agent:note:delete', 3, 3040, 3, 2, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3131);

-- ------------------------------------------------------------
-- 超级管理员授权（role_id=1）：笔记管理下两个按钮
-- 沿 agent-domain.sql 的 WHERE NOT EXISTS 写法（既有行跳过）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3130
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3130);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3131
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3131);
