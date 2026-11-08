-- ============================================================
-- M4.1 目标（objective）域菜单/权限 SQL
-- 在目标 MySQL 5.7（库 agent-console）执行（init-db.sh 顺序追加）
--
-- 说明：
--   1. 本脚本专供 MySQL 5.7（H2 不执行本文件；H2 dev 用 agent-domain.sql 的
--      t_objective 建表 + AutoDDL，权限菜单由生产库执行本文件）。
--   2. 幂等：全部 INSERT 用 WHERE NOT EXISTS 守卫（沿用 m13/m35 模式），
--      可重复执行，不覆盖别模块已有 id 段。
--   3. ID 段（契约 §3 方案 A 一级目录并列）：3090 目标管理一级目录 +
--      3091 目标列表页面菜单（type=2）+ 按钮 3180-3183
--      （objective:list/create/edit/delete）。
--   4. 默认角色 1（super_admin）授权 3090/3091 + 3180-3183（幂等补齐）。
--
-- 2026-09-01 修复（初版缺陷）：
--   初版只建 3090 目录 + 3180-3183 按钮，缺 type=2 页面菜单节点——
--   前端 buildRoutesFromMenus 只给 type=2 且有 component 的节点注册路由，
--   侧边栏「目标管理」目录下全是 type=3 按钮被过滤，导致：
--   1) 无子菜单可点；2) /objective/list 路由未注册，点击 404。
--   修复（与 3040 笔记列表/3061 笔记本列表惯例对齐）：
--   - 3090 route_path 从 /objective/list 改为前缀 /objective
--   - 新增 3091 目标列表（type=2，component=system/objective/index）
--   - 按钮 3180-3183 挂载点从 3090 改为 3091（惯例：按钮挂 type=2 菜单下）
--   现网已同步手工修复，本文件回写保证下次清库重建不再踩坑。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 一级目录：目标管理（3090）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3090, '目标管理', 'objective', 1, 0, 1, '/objective', NULL, 'ph:target', 7, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3090);

-- ------------------------------------------------------------
-- 2. 页面菜单：目标列表（3091，type=2，注册路由 + 渲染侧边栏）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3091, '目标列表', 'objective', 2, 3090, 2, '/objective/list', 'system/objective/index', 'ph:target', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3091);

-- ------------------------------------------------------------
-- 3. 按钮权限码（3180-3183，挂目标列表页面菜单下）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3180, '目标查询', 'objective:list', 3, 3091, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3180);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3181, '目标新建', 'objective:create', 3, 3091, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3181);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3182, '目标编辑', 'objective:edit', 3, 3091, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3182);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3183, '目标删除', 'objective:delete', 3, 3091, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3183);

-- ------------------------------------------------------------
-- 4. 超级管理员（role_id=1）授权目标域（幂等补齐）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3090
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3090);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3091
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3091);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3180
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3180);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3181
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3181);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3182
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3182);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3183
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3183);

-- ------------------------------------------------------------
-- 5. 存量库修正（幂等：仅当旧形态残留时修正）
-- ------------------------------------------------------------
-- 初版脚本建出的 3090 route_path=/objective/list（目录不应带页面路径）
UPDATE `t_permission` SET `route_path` = '/objective'
WHERE `id` = 3090 AND `route_path` = '/objective/list';
-- 初版按钮挂在目录 3090 下，挪到页面菜单 3091 下
UPDATE `t_permission` SET `parent_id` = 3091, `level` = 3
WHERE `id` IN (3180, 3181, 3182, 3183) AND `parent_id` = 3090;

-- ============================================================
-- M4.2 台账（ledger）域菜单/权限 SQL（追加段）
--
-- ⚠️ MR !45 教训（2026-09-01 生产事故）：菜单必须三件套齐全！
--    目录(type=1) + 页面菜单(type=2 带 component) + 按钮(type=3 挂 type=2 下)。
--    缺 type=2 页面菜单 = 入库了侧边栏也不显示、路由不注册（点击 404）。
--
-- ID 段：3120 台账管理一级目录 + 3121 台账列表页面菜单（type=2）+
--        按钮 3184-3187（ledger:list/create/edit/delete）。
-- 幂等：全部 INSERT 用 WHERE NOT EXISTS 守卫，可重复执行。
--
-- ⚠️ 2026-09-01 ID 冲突修复：初版时 m13-menu.sql 的「审计查询」按钮占用 3120，
--    守卫导致 3120 台账目录被跳过、3121 挂到审计按钮下（侧边栏无台账入口）。
--    已将审计按钮改用 3160（见 m13-menu.sql），本段 3120/3121 恢复正常；
--    现网已手工同步修正（3120→3160 + 补 3120 目录 + 3121 挂回目录）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 一级目录：台账管理（3120，type=1，route_path 前缀 /ledger，component=NULL）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3120, '台账管理', 'ledger', 1, 0, 1, '/ledger', NULL, 'ph:notebook', 8, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3120);

-- ------------------------------------------------------------
-- 2. 页面菜单：台账列表（3121，type=2，注册路由 + 渲染侧边栏）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3121, '台账列表', 'ledger', 2, 3120, 2, '/ledger/list', 'system/ledger/index', 'ph:notebook', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3121);

-- ------------------------------------------------------------
-- 3. 按钮权限码（3184-3187，挂台账列表页面菜单下）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3184, '台账查询', 'ledger:list', 3, 3121, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3184);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3185, '台账新建', 'ledger:create', 3, 3121, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3185);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3186, '台账编辑', 'ledger:edit', 3, 3121, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3186);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3187, '台账删除', 'ledger:delete', 3, 3121, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3187);

-- ------------------------------------------------------------
-- 4. 超级管理员（role_id=1）授权台账域（幂等补齐）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3120
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3120);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3121
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3121);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3184
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3184);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3185
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3185);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3186
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3186);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3187
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3187);
