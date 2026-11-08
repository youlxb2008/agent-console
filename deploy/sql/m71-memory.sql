-- ============================================================
-- M7.1 记忆与遗忘模块：t_memory 增量 DDL（存量库执行）
-- 在目标 MySQL 5.7（库 agent-console）执行（幂等，可重复执行）
--
-- 说明：
--   1. 全新库无需执行本文件——agent-domain.sql 已含同款 t_memory DDL
--      （SqlInitializer 启动时自动执行）。
--   2. 本文件供存量库增量：CREATE TABLE IF NOT EXISTS 幂等，已有库重跑不覆盖数据。
--   3. 索引名按任务书 §2.1：idx_agent_status / idx_agent_type / idx_last_accessed。
--   4. 菜单/权限与 cron 种子见 m71-menu.sql（M2 交付物，另行执行）。
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_memory` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（即对外引用键）',
  `agent_id`         BIGINT       NOT NULL COMMENT '归属 agent（t_agent.id），每 agent 私域记忆库',
  `mem_type`         VARCHAR(16)  NOT NULL COMMENT 'fact/decision/preference/lesson/event',
  `source`           VARCHAR(16)  NOT NULL COMMENT 'dialogue/self/system',
  `title`            VARCHAR(255) NOT NULL COMMENT '一句话摘要',
  `content_md`       TEXT         NOT NULL COMMENT '结论正文',
  `tags`             VARCHAR(512) DEFAULT NULL COMMENT '逗号分隔标签',
  `ref_url`          VARCHAR(512) DEFAULT NULL COMMENT 'AC 笔记深链指针（单向引用）',
  `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '1=active 2=archived 3=superseded',
  `superseded_by`    BIGINT       DEFAULT NULL COMMENT '取代本条的新记忆 id（status=3 时有值）',
  `weight`           INT          NOT NULL DEFAULT 0 COMMENT '衰减权重（memoryDecayJob 重算）',
  `access_count`     INT          NOT NULL DEFAULT 0 COMMENT '检索命中次数',
  `last_accessed_at` DATETIME     DEFAULT NULL COMMENT '最后命中时间',
  `confirmed_at`     DATETIME     DEFAULT NULL COMMENT '最后确认时间',
  `expired_at`       DATETIME     DEFAULT NULL COMMENT '预留字段（本版本未用）',
  `create_time`      DATETIME     NULL,
  `update_time`      DATETIME     NULL,
  `is_deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0未删除 1已删除（软删）',
  PRIMARY KEY (`id`),
  KEY `idx_agent_status` (`agent_id`, `status`),
  KEY `idx_agent_type` (`agent_id`, `mem_type`),
  KEY `idx_last_accessed` (`status`, `last_accessed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='agent 长期记忆';
