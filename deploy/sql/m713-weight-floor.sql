-- ============================================================
-- M7.1-M3a 记忆 weight 保底机制：t_memory 增量 DDL（存量库执行）
-- dev 库可直接执行；prod 建议手动执行。
--
-- 说明：
--   1. 全新库无需执行本文件——db/mysql/agent-domain.sql 的 t_memory DDL 已含同款列
--      （SqlInitializer 启动时自动执行）。
--   2. MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，用存储过程查 information_schema
--      实现幂等（重复执行不报错、不重复加列）。
--   3. 执行方式：mysql client source 本文件（含 DELIMITER 指令，须用 mysql client 执行）。
-- ============================================================

DELIMITER $$
DROP PROCEDURE IF EXISTS m713_add_weight_floor$$
CREATE PROCEDURE m713_add_weight_floor()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_memory'
      AND COLUMN_NAME = 'weight_floor'
  ) THEN
    ALTER TABLE `t_memory`
      ADD COLUMN `weight_floor` INT NOT NULL DEFAULT 0 COMMENT '权重保底线（0=不保底，M7.1-M3a）';
  END IF;
END$$
DELIMITER ;

CALL m713_add_weight_floor();
DROP PROCEDURE IF EXISTS m713_add_weight_floor;
