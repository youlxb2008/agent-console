-- ============================================================
-- M4.37 总线调试菜单 SQL（bus-sessionkey-fix，v4.37）
-- 功能：总线 sessionKey 落点修复 + admin 总线调试页
-- 执行环境：MySQL 5.7（dev / prod，库 agent-console）
--   dev 执行（发版 checklist 项）；prod 发布时同步执行
--
-- 说明：
--   1. 幂等：WHERE NOT EXISTS 守卫 + 角色授权防重，可重复执行。
--   2. ⚠️ id 执行前查占用（种子漂移是常态，历史上 507 被絮语管理占过）：
--        SELECT id, name FROM t_permission WHERE id = 3200;
--      核实（2026-10-06）：max(id)=3199，3200 空闲。
--   3. 页面权限点复用既有 bus:message:list（查路由/消息流转）与
--      bus:message:send（调试发送），本 SQL 不新增按钮级权限行。
--   4. routes.ts 已同步静态注册 /system/bus-debug（两处齐，漏一处白屏）。
-- ============================================================

-- ------------------------------------------------------------
-- 二级菜单：总线调试（挂系统管理(1) 下，component=system/bus/debug）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3200, '总线调试', 'bus:debug', 2, 1, 2, '/system/bus-debug', 'system/bus/debug', 'ph:bug', 31, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3200);

-- ------------------------------------------------------------
-- 超级管理员（role_id=1）授权（防重）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`)
SELECT 1, 3200
WHERE NOT EXISTS (
  SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3200
);
