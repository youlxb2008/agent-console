-- ============================================================
-- notes-v3：笔记无主化 N:N（清表重建版）
-- 设计：docs/版本/agent-v3_20260830/agent-v3_20260830-实现计划.md §2/§4
-- 目标库：MySQL 5.7+，库 agent-console
--
-- 前置确认（已确认）：现有笔记均为测试数据，不做数据迁移
--   - 不建备份表、不写 NoteMigrator、不做两段式发布
--   - 执行前建议 mysqldump 快照 t_note_meta/t_permission（§4.3 回滚预案）
--
-- 文件系统配套（§4.2）：部署前手动删除 NOTES_ROOT/agent-*/ 旧目录；
--   NOTES_ROOT/notes/ 由应用启动时 Files.createDirectories 自动创建
-- ============================================================

-- ------------------------------------------------------------
-- 1. 重建笔记索引表（v3 结构：🔥 删 agent_id/file_path，✨ 增 dir_path）
--    真相源 = NOTES_ROOT/notes/{nid}.md，路径由 nid 派生不再入库；
--    幂等锚点改为 nid，agent 维度过滤改走 t_agent_resource JOIN
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_note_meta`;
CREATE TABLE `t_note_meta` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（纯内部，不外露）',
  `nid`         VARCHAR(16)  NOT NULL COMMENT '对外引用键 n_+10位base62，随机生成终身不变；覆写保留',
  `dir_path`    VARCHAR(512) NOT NULL DEFAULT '' COMMENT '目录路径（树依据），如 工作日志/2026-08；根目录为空串',
  `title`       VARCHAR(200) NOT NULL COMMENT '笔记标题（管理端搜索字段）',
  `tags`        VARCHAR(500) NULL COMMENT '逗号分隔标签',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_nid` (`nid`),
  KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='笔记索引（真相源=NOTES_ROOT/notes/{nid}.md）';

-- ------------------------------------------------------------
-- 2. ✨ 新增 t_agent_resource（agent 与资源 N:N 通用关联表）
--    resource_type 扩展位：note=笔记；未来 chat/task/kb 直接挂新 type，本表不再变更
--    纯关系行：无 update_time/is_deleted，解除关联=物理 DELETE
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_resource` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id`      BIGINT      NOT NULL COMMENT 't_agent.id（逻辑外键）',
  `resource_type` VARCHAR(32) NOT NULL COMMENT '资源类型：note=笔记；预留 chat/task/kb',
  `resource_id`   VARCHAR(64) NOT NULL COMMENT '资源外部键：note 填 nid（n_+10位base62）',
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联时间',
  `create_by`     VARCHAR(64) NULL COMMENT '关联操作人（管理员用户名/system-migration）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_resource` (`agent_id`, `resource_type`, `resource_id`),
  KEY `idx_resource` (`resource_type`, `resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='agent 与资源 N:N 通用关联表';

-- ------------------------------------------------------------
-- 3. 菜单升级：笔记管理升顶级目录（§2.4 目标态，全部幂等）
--    3050 新顶级目录 /note；3040 从 Agent 管理下迁至 3050（路径/组件调整）；
--    3132 新增「关联维护」按钮（双向弹窗共用 agent:note:link）
-- ------------------------------------------------------------
-- 3.1 新增顶级目录 3050「笔记管理」（与 Agent 管理平级，sort=4 排其后）
INSERT IGNORE INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
VALUES (3050, '笔记管理', 'agent:note', 1, 0, 1, '/note', NULL, 'ph:note-pencil', 4, 1);

-- 3.2 3040 迁移：父级 3000→3050，路径 /agent/notes→/note/list，组件迁 system/note/index
--     （t_permission 主键幂等：已有行一律纠正到目标态，全新库该 UPDATE 影响 0 行）
UPDATE `t_permission`
SET `name` = '笔记列表', `parent_id` = 3050, `level` = 2,
    `route_path` = '/note/list', `component` = 'system/note/index',
    `icon` = 'ph:note-pencil', `sort_order` = 1, `status` = 1
WHERE `id` = 3040;

-- 3.3 新增按钮 3132「关联维护」（agent:note:link）
INSERT IGNORE INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
VALUES (3132, '关联维护', 'agent:note:link', 3, 3040, 3, 3, 1);

-- 3.4 super_admin（role_id=1）补齐角色-权限（含 3130/3131 旧按钮随迁兜底）
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3050
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3050);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3040
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3040);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3130
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3130);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3131
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3131);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3132
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3132);

-- ------------------------------------------------------------
-- 4. 验证（执行后人工核对）
--    SHOW CREATE TABLE t_note_meta;      -- 应含 dir_path，无 agent_id/file_path
--    SHOW CREATE TABLE t_agent_resource; -- 唯一键 uk_agent_resource
--    SELECT id,name,parent_id,route_path,component FROM t_permission WHERE id IN (3000,3010,3020,3030,3040,3050,3130,3131,3132);
--    菜单树应出现顶级「笔记管理 /note」→「笔记列表 /note/list」
-- ============================================================
