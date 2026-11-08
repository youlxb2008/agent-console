-- ============================================================
-- M4.29 总线投递通道方向级路由（bus-route-channel）DDL
-- 功能：总线投递通道方向级可切换 WS/API
-- 执行环境：MySQL 5.7（dev / prod，库 agent-console），手动执行
--
-- 说明：
--   1. 幂等：建表 IF NOT EXISTS，可重复执行；无加列场景。
--   2. 全新库无需执行本文件：t_bus_route 已并入
--      src/backend/src/main/resources/db/mysql/agent-domain.sql
--      （SqlInitializer 启动自动执行）。
--   3. 部署配套 env（在 dev/prod .env 注入，compose 已透传 BUS_API_TARGET_1..8）：
--      BUS_API_TARGET_{n} = {agentId}|{baseUrl}|{apiKey}（n=1..8），
--      例：BUS_API_TARGET_1=2|http://your-host:8900|apikey-xxxx
--      该 agent 无对应配置时 api 模式投递自动降级 ws（t_agent_message.last_error 记备注）。
--   4. 语义：
--      - 无配置行 = ws（向后兼容，存量行为不变）；有行按 channel 走。
--      - from = to 的配置行校验拒绝（应用层 422，自投不参与路由）。
--      - 管理端保存即时生效（dispatcher 投递前现查 t_bus_route，不缓存）。
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_bus_route` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `from_agent_id` BIGINT      NOT NULL COMMENT '来源 agent id（t_agent.id）',
  `to_agent_id`   BIGINT      NOT NULL COMMENT '目标 agent id（t_agent.id）',
  `channel`       VARCHAR(8)  NOT NULL COMMENT '投递通道：ws / api',
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_bus_route_from_to` (`from_agent_id`, `to_agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='总线投递通道方向级路由（bus-route-channel）';
