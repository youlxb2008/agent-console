-- ============================================================
-- M4.17 笔记本三图体系：t_notebook 加 logo / hero 两列
-- 在目标 MySQL 5.7（库 agent-console）执行（幂等，可重复执行）
--
-- 说明：
--   1. 本脚本专供 MySQL 5.7（H2 不执行；H2 dev 由 SqlInitializer 启动时
--      跑 schema.sql/schema-agent.sql，自动同步列 IF NOT EXISTS）。
--   2. MySQL 5.7 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS，
--      用 INFORMATION_SCHEMA 检测 + PREPARE/EXECUTE 动态 SQL 守卫。
--   3. 列宽与原 cover 列对齐（VARCHAR(512)，cover 列也是 VARCHAR(255)，
--      这里取与 cover 同风格 VARCHAR(512) 兼容后续扩展）。
--   4. 全新库已由 agent-domain.sql 建表自带 cover 列，本文件只补 logo/hero。
-- ============================================================

-- ------------------------------------------------------------
-- 1. t_notebook 幂等加列：logo（笔记页左上角小标识，方图）
-- ------------------------------------------------------------
SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_notebook' AND COLUMN_NAME = 'logo'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_notebook` ADD COLUMN `logo` VARCHAR(512) NULL COMMENT ''笔记本 logo URL（笔记页左上角小标识）'' AFTER `cover`', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. t_notebook 幂等加列：hero（笔记页空态中央展示图，宽幅）
-- ------------------------------------------------------------
SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_notebook' AND COLUMN_NAME = 'hero'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_notebook` ADD COLUMN `hero` VARCHAR(512) NULL COMMENT ''笔记本 hero URL（笔记页空态中央展示图）'' AFTER `logo`', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
