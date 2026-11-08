-- ============================================================
-- M7.1 记忆（memory）域菜单/权限 SQL + memoryDecayJob cron 种子
-- 在目标 MySQL 5.7（库 agent-console）执行（init-db.sh 顺序追加）
--
-- 说明：
--   1. 本脚本专供 MySQL 5.7（H2 不执行本文件；H2 dev 权限菜单由测试内自建）。
--   2. 幂等：全部 INSERT 用 WHERE NOT EXISTS 守卫（沿用 m4-menu.sql 模式）。
--   3. ID 段：3190 记忆管理页面菜单（type=2，挂 3000 agent 管理一级目录下）+
--      按钮 3191-3194（memory:list/archive/restore/delete，挂 3190 下）。
--      ⚠️ 菜单三件套教训（MR !45）：页面菜单 type=2 带 component 才会注册路由。
--   4. 默认角色 1（super_admin）授权 3190 + 3191-3194（幂等补齐）。
--   5. t_cron_job 种子 status=0（禁用）——用户明确指示「先别着急遗忘」，
--      自动衰减的实际启用需用户明确指示，届时 UPDATE status=1 即可。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 页面菜单：记忆管理（3190，type=2，挂 3000 目录下，注册路由 + 渲染侧边栏）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3190, '记忆管理', 'memory', 2, 3000, 2, '/agent/memory', 'system/agent/memory', 'ph:brain', 6, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3190);

-- ------------------------------------------------------------
-- 2. 按钮权限码（3191-3194，挂记忆管理页面菜单下）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3191, '记忆查询', 'memory:list', 3, 3190, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3191);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3192, '记忆归档', 'memory:archive', 3, 3190, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3192);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3193, '记忆恢复', 'memory:restore', 3, 3190, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3193);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3194, '记忆删除', 'memory:delete', 3, 3190, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3194);

-- ------------------------------------------------------------
-- 3. 超级管理员（role_id=1）授权记忆域（幂等补齐）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3190
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3190);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3191
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3191);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3192
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3192);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3193
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3193);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3194
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3194);

-- ------------------------------------------------------------
-- 4. memoryDecayJob cron 种子（status=0 禁用；启用需用户明确指示）
--    handler_class=memoryDecayJob（MemoryDecayJob @PostConstruct 注册）
--    cron 每日 04:00（Spring 六段式，Asia/Shanghai 由调度引擎时区决定）
-- ------------------------------------------------------------
INSERT INTO `t_cron_job` (`name`, `cron_expr`, `handler_class`, `params`, `status`)
SELECT '记忆衰减（memoryDecayJob）', '0 0 4 * * ?', 'memoryDecayJob', NULL, 0
WHERE NOT EXISTS (SELECT 1 FROM `t_cron_job` WHERE `handler_class` = 'memoryDecayJob');
