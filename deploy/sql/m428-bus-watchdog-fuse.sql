-- ============================================================
-- M4.28 总线超时保险丝（bus-watchdog-fuse）DDL
-- 功能：agc 总线超时保险丝
-- 执行环境：MySQL 5.7（dev / prod，库 agent-console），手动执行
--
-- 说明：
--   1. 幂等：加列走 INFORMATION_SCHEMA 检测 + PREPARE/EXECUTE 动态 SQL
--      （MySQL 5.7 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS）；
--      建表 IF NOT EXISTS，可重复执行。
--   2. 全新库无需执行本文件：expect_reply_minutes 列与 t_bus_topic_watch
--      建表已并入 src/backend/src/main/resources/db/mysql/agent-domain.sql
--      （SqlInitializer 启动自动执行）。
--   3. 部署配套 env（在 dev/prod .env 注入，compose 已透传）：
--      BUS_FUSE_DEFAULT_MINUTES（缺省 30）/ BUS_ALERT_SESSION（形态 websocket:<uuid>，
--      超时告警固定落管理员主会话窗口，不新开会话）。
--   4. 语义：
--      - expect_reply_minutes：消息级回音时限（分钟，1~1440；NULL=走全局默认）。
--      - t_bus_topic_watch：topic 等待段告警去重状态（alerted_message_id = 当前等待段
--        锚点即末条消息 id，恒非空；同段只告警一次，新段可再告警；
--        alert_count=0 = 发现时 BUS_ALERT_SESSION 未配置仅 warn；
--        等待段结束（回音/系统消息收尾/自投线程）由扫描器删行销案）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. t_agent_message 幂等加列：expect_reply_minutes（消息级回音时限）
-- ------------------------------------------------------------
SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_agent_message' AND COLUMN_NAME = 'expect_reply_minutes'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_agent_message` ADD COLUMN `expect_reply_minutes` INT NULL COMMENT ''消息级回音时限（分钟，1~1440）；NULL=走全局默认 BUS_FUSE_DEFAULT_MINUTES'' AFTER `model`', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. t_bus_topic_watch（等待段告警去重状态，保险丝扫描器维护）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_bus_topic_watch` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic`              VARCHAR(100) NOT NULL COMMENT '原始 topic（无 [fuse] 前缀），唯一',
  `alerted_message_id` BIGINT       NOT NULL COMMENT '当前等待段锚点（末条消息 id，恒非空；同段只告警一次，新段可再告警）',
  `alert_count`        INT          NOT NULL DEFAULT 0 COMMENT '累计投递告警次数；0=发现时 BUS_ALERT_SESSION 未配置仅 warn',
  `last_alert_at`      DATETIME     NULL COMMENT '最近一次告警时间',
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_bus_topic_watch_topic` (`topic`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='总线超时保险丝等待段状态（bus-watchdog-fuse）';
