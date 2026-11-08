-- ============================================================
-- M3.5 笔记本（notebook）域菜单/权限 + t_note_meta 加列 SQL
-- 在目标 MySQL 5.7（库 agent-console）执行
--
-- 说明：
--   1. 本脚本专供 MySQL 5.7（H2 不执行本文件）。
--   2. 【重要】t_note_meta 新增 notebook_id 列：
--      MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，故用 INFORMATION_SCHEMA 检测 +
--        PREPARE/EXECUTE 动态 SQL，实现幂等加列（列/索引已存在则跳过）。
--      全新库已由 agent-domain.sql 建表自带该列，本段执行时自动跳过，无副作用。
--   3. 菜单/权限：ID 段与 agent-domain.sql 的 v3.5 段一致，
--      v3.5.1 收敛为最终结构：
--        - 笔记管理(3050) 一级下收纳：3040 笔记列表 + 3061 笔记本列表（不再单独建 3060 一级目录）
--        - 新增日志管理(3080) 一级目录，审计日志(3030) 从 Agent 管理(3000) 迁入
--      （3061 + 3170-3175 + 3080 全部 WHERE NOT EXISTS 幂等；3030 用 UPDATE 幂等纠正）
-- ============================================================

-- ------------------------------------------------------------
-- 1. t_note_meta 幂等加列：notebook_id（所属笔记本） + 索引
--    （动态 SQL 仅 MySQL 语法；DATABASE() 限定当前库，避免误改同名表）
-- ------------------------------------------------------------
SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_meta' AND COLUMN_NAME = 'notebook_id'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_note_meta` ADD COLUMN `notebook_id` BIGINT NOT NULL COMMENT ''所属笔记本 t_notebook.id'' AFTER `nid`', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_meta' AND INDEX_NAME = 'idx_t_note_meta_notebook'
);
SET @ddl2 = IF(@idx_exists = 0,
  'ALTER TABLE `t_note_meta` ADD KEY `idx_t_note_meta_notebook` (`notebook_id`)', 'SELECT 1');
PREPARE stmt FROM @ddl2;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. 二级菜单：笔记本列表（v3.5.1：直接挂「笔记管理」3050 下，不再建 3060 一级目录）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3061, '笔记本列表', 'notebook', 2, 3050, 2, '/notebook/list', 'system/notebook/index', 'ph:book', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3061);

-- 存量库兜底：若 3061 曾挂在废弃的一级目录 3060 下，纠正到 3050，并清理 3060
UPDATE `t_permission` SET `parent_id` = 3050, `level` = 2, `sort_order` = 2
WHERE `id` = 3061 AND `parent_id` <> 3050;
DELETE FROM `t_permission` WHERE `id` = 3060;
DELETE FROM `t_role_permission` WHERE `permission_id` = 3060;

-- ------------------------------------------------------------
-- 3. 一级目录：日志管理（收纳审计日志等日志类子菜单，存在则跳过）
--    + 审计日志(3030) 从 Agent 管理(3000) 迁入（幂等 UPDATE）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3080, '日志管理', 'log', 1, 0, 1, '/log', NULL, 'ph:scroll', 6, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3080);

UPDATE `t_permission`
SET `parent_id` = 3080, `level` = 2, `route_path` = '/log/audit', `sort_order` = 1
WHERE `id` = 3030 AND `parent_id` <> 3080;

-- ------------------------------------------------------------
-- 4. 三级按钮（笔记本列表下，6 个权限码）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3170, '笔记本查询', 'notebook:list', 3, 3061, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3170);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3171, '笔记本新建', 'notebook:create', 3, 3061, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3171);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3172, '笔记本编辑', 'notebook:edit', 3, 3061, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3172);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3173, '笔记本删除', 'notebook:delete', 3, 3061, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3173);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3174, '关联 Agent 查询', 'notebook:agent:list', 3, 3061, 3, 5, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3174);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3175, '关联 Agent 维护', 'notebook:agent:link', 3, 3061, 3, 6, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3175);

-- ------------------------------------------------------------
-- 5. 超级管理员（role_id=1）授权 notebook 域 + 日志管理（幂等补齐）
--    （3060 已废弃删除不再授权；3080/3061/3170-3175 全授权）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3080
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3080);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3061
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3061);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3170
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3170);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3171
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3171);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3172
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3172);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3173
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3173);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3174
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3174);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3175
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3175);