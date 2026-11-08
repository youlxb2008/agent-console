-- ============================================================
-- 笔记 v2 迁移 SQL（契约 data-model.md §2.4 / §2.5）
-- 目标库：MySQL 5.7+，库 agent-console
--
-- 说明：
--   - 🔥 审批流整体删除：DROP t_note / t_note_review（存量 = M1.5 验收测试数据 1 篇，不迁移）
--   - ✨ 新增 t_note_meta 纯索引表（笔记内容真相源移入 NOTES_ROOT 文件系统，见 data-model.md §5）
--   - 本文件供存量库手动执行；dev/H2 侧 DDL 由 agent-domain.sql + schema 保持一致
-- ============================================================

-- ------------------------------------------------------------
-- 2.5 🔥 废弃表处置
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_note`;
DROP TABLE IF EXISTS `t_note_review`;

-- ------------------------------------------------------------
-- 2.4 ✨ t_note_meta（新增：笔记索引表）
-- ------------------------------------------------------------
CREATE TABLE `t_note_meta` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（纯内部，不外露）',
  `nid`         VARCHAR(16)  NOT NULL COMMENT '对外引用键 n_+10位base62，随机生成终身不变；覆写保留原 nid',
  `agent_id`    BIGINT       NOT NULL COMMENT '作者（t_agent.id）；目录 agent-{id}/ 由后端强制拼接',
  `file_path`   VARCHAR(512) NOT NULL COMMENT '相对 NOTES_ROOT，如 agent-1/工作日志/xxx.md',
  `title`       VARCHAR(200) NOT NULL COMMENT '笔记标题（=文件名去 .md），管理端搜索字段',
  `tags`        VARCHAR(500) NULL COMMENT '逗号分隔标签；[本版本未用] 仅落库，无 UI 无查询',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_nid` (`nid`),
  UNIQUE KEY `uk_agent_path` (`agent_id`, `file_path`),
  KEY `idx_agent_time` (`agent_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='笔记索引（真相源在文件系统）';
