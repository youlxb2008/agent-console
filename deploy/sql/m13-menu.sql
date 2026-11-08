-- ============================================================
-- M1.3 管理端前端 agent 模块菜单 SQL（在目标 MySQL 5.7 执行）
-- 目标库：agent-console
--
-- 说明：
--   1. 菜单/权限统一存 t_permission（无 t_menu 表）。
--   2. M1.2a-1 已创建 ID 段 3000 目录 + 3010-3040 菜单 + 3100-3131 按钮，
--      当时 component 指向 views/agent/（contracts §4.1 旧路径，文件未建）。
--      本包页面位于 views/system/agent/（任务书 M1.3 §A），
--      故这里用 INSERT ... ON DUPLICATE KEY UPDATE 幂等修正 component，
--      只改路径、不覆盖 name/icon/sort 等用户可能改动的字段。
--   3. 超级管理员（role_id=1）授权用 INSERT IGNORE 幂等补齐。
--   4. 路由/菜单结构保持 M1.2a-1 不变：
--        /agent/agents → Agent 列表（system/agent/index）
--        /agent/keys   → API Key 管理（system/agent/key）
--        /agent/audit  → 审计日志（system/agent/audit）
--        /agent/notes  → 笔记审核（system/agent/review）
-- ============================================================

-- ------------------------------------------------------------
-- 一级目录：Agent 管理（已存在则保持不变）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3000, 'Agent 管理', 'agent', 1, 0, 1, '/agent', NULL, 'ph:robot', 3, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3000);

-- ------------------------------------------------------------
-- 二级菜单（修正 component 指向 M1.3 页面；存在即更新 component，不覆盖其它字段）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
VALUES (3010, 'Agent 列表', 'agent:agent', 2, 3000, 2, '/agent/agents', 'system/agent/index', 'ph:robot', 1, 1)
ON DUPLICATE KEY UPDATE `component` = VALUES(`component`);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
VALUES (3020, 'API Key 管理', 'agent:key', 2, 3000, 2, '/agent/keys', 'system/agent/key', 'ph:key', 2, 1)
ON DUPLICATE KEY UPDATE `component` = VALUES(`component`);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
VALUES (3030, '审计日志', 'agent:audit', 2, 3000, 2, '/agent/audit', 'system/agent/audit', 'ph:clipboard-text', 3, 1)
ON DUPLICATE KEY UPDATE `component` = VALUES(`component`);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
VALUES (3040, '笔记列表', 'agent:note', 2, 3050, 2, '/note/list', 'system/note/index', 'ph:note-pencil', 1, 1)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `parent_id` = VALUES(`parent_id`),
                        `level` = VALUES(`level`), `route_path` = VALUES(`route_path`),
                        `component` = VALUES(`component`), `sort_order` = VALUES(`sort_order`);

-- ------------------------------------------------------------
-- 三级按钮（M1.2a-1 已入库，此处幂等补齐，避免全新库场景缺失）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3100, 'Agent 查询', 'agent:agent:list', 3, 3010, 3, 1, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3100);
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3101, 'Agent 新增/编辑', 'agent:agent:save', 3, 3010, 3, 2, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3101);
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3102, 'Agent 删除', 'agent:agent:delete', 3, 3010, 3, 3, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3102);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3110, 'Key 查询', 'agent:key:list', 3, 3020, 3, 1, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3110);
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3111, 'Key 创建', 'agent:key:create', 3, 3020, 3, 2, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3111);
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3112, 'Key 吊销', 'agent:key:revoke', 3, 3020, 3, 3, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3112);

-- 2026-09-01 修复（M4.2 ID 冲突）：审计查询按钮原用 3120，与 m4-menu.sql
-- 台账管理一级目录（3120）冲突——WHERE NOT EXISTS 守卫导致台账目录被跳过、
-- 3121 台账列表挂到审计按钮下（侧边栏无台账入口）。审计按钮改用空闲 ID 3160，
-- 现网已手工同步修正（3120→3160 + 补 3120 台账目录 + 3121 挂回目录）。
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3160, '审计查询', 'agent:audit:list', 3, 3030, 3, 1, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3160);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3130, '笔记查询', 'agent:note:list', 3, 3040, 3, 1, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3130);
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3131, '笔记审核', 'agent:note:review', 3, 3040, 3, 2, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3131);

-- ------------------------------------------------------------
-- 超级管理员授权（role_id=1，INSERT IGNORE 幂等，唯一键 uk_t_role_perm_role_perm 去重）
-- ------------------------------------------------------------
INSERT IGNORE INTO `t_role_permission` (`role_id`, `permission_id`)
VALUES
  (1, 3000), (1, 3010), (1, 3020), (1, 3030), (1, 3040),
  (1, 3100), (1, 3101), (1, 3102),
  (1, 3110), (1, 3111), (1, 3112),
  (1, 3160),
  (1, 3130), (1, 3131);
