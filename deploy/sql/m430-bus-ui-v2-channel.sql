-- ============================================================
-- M4.30 总线交互改版 v2（bus-ui-v2）DDL
-- 功能：agc 总线交互改版 v2
-- 执行环境：MySQL 5.7（dev / prod，库 agent-console）
--   dev 可自动执行（幂等无害）；prod 建议手动执行
--
-- 说明：
--   1. 幂等：加列走 INFORMATION_SCHEMA 检测 + PREPARE/EXECUTE 动态 SQL
--      （MySQL 5.7 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS），可重复执行。
--   2. 全新库无需执行本文件：channel 列已并入
--      src/backend/src/main/resources/db/mysql/agent-domain.sql
--      （SqlInitializer 启动自动执行）。
--   3. 语义：channel = 实际投递通道，dispatcher 投递成功时回填 ws/api；
--      api 目标缺 BUS_API_TARGET 配置降级 ws 时也写 ws（last_error 留备注）。
--      历史行（本列上线前）NULL，前端显示「—」。
-- ============================================================

-- ------------------------------------------------------------
-- 1. t_agent_message 幂等加列：channel（实际投递通道）
-- ------------------------------------------------------------
SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_agent_message' AND COLUMN_NAME = 'channel'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_agent_message` ADD COLUMN `channel` VARCHAR(8) NULL COMMENT ''实际投递通道：ws/api（dispatcher 投递成功回填；api 降级 ws 也写 ws）；历史行 NULL（bus-ui-v2）'' AFTER `status`',
  'SELECT ''channel 列已存在，跳过'' AS note');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
