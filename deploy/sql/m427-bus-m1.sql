-- ============================================================
-- M1 agent 消息总线（bus-m1）DDL + 权限/菜单种子
-- 功能：agc 消息总线 M1
-- 执行环境：MySQL 5.7（dev / prod，库 agent-console），手动执行
--
-- 说明：
--   1. 幂等：建表 IF NOT EXISTS + 种子 WHERE NOT EXISTS 守卫，可重复执行。
--   2. 执行前先查 id 占用（种子漂移是常态）：
--      SELECT id, permission_key FROM t_permission WHERE id IN (3196,3197,3198,3199);
--      若已被占用请改 id 并同步 parent_id 引用（页面 3196 + 按钮 3197-3199）。
--   3. 全新库无需执行本文件：t_agent_message 建表与种子已并入
--      src/backend/src/main/resources/db/mysql/agent-domain.sql（SqlInitializer 启动自动执行）。
--   4. 权限点：bus:message:list / bus:message:retry / bus:message:send；
--      菜单：系统管理(1) → 消息总线(3196)，component=system/bus/index。
--   5. agent API key 补 scope bus:write 不在本文件（走管理端 PUT /agent/admin/keys/{id}，
--      整体覆盖语义，须带回现有全量 scopes 再 PUT）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. t_agent_message（方案 §3.1；索引名带表前缀——H2 约束名全局唯一惯例）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_message` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `message_id`    VARCHAR(64)  NOT NULL COMMENT '幂等键，服务端生成 UUID',
  `from_agent_id` INT          NOT NULL COMMENT '生产者，由 X-API-Key 推导，body 不可指定（防伪造）',
  `to_agent_id`   INT          NOT NULL COMMENT '消费者，逻辑关联 t_agent.id',
  `msg_type`      VARCHAR(16)  NOT NULL COMMENT 'notify/alert（M1）；request M1 禁用',
  `topic`         VARCHAR(100) NOT NULL COMMENT '场景标签：alpha-exception / patrol-alert 等',
  `title`         VARCHAR(255) NOT NULL COMMENT '标题',
  `payload`       TEXT         NULL COMMENT '消息体 JSON，≤8KB',
  `session_key`   VARCHAR(128) NULL COMMENT '目标会话；空=dispatcher 自动新建一次性会话；投递后回填实际 sessionKey',
  `model`         VARCHAR(64)  NULL COMMENT '指定本条消息处理模型；空=收件人默认 preset；须经目标白名单校验',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待投递/1已送达(协议层)/2已读(M2)/3失败',
  `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT '投递重试次数（指数退避 ≤5 次后置 3）',
  `last_error`    VARCHAR(500) NULL COMMENT '最近一次投递失败原因（管理端可见）',
  `delivered_at`  DATETIME     NULL COMMENT '送达时间（协议层）',
  `read_at`       DATETIME     NULL COMMENT '已读时间（M2 预留）',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_agent_message_id` (`message_id`),
  KEY `idx_t_agent_message_to_status` (`to_agent_id`, `status`),
  KEY `idx_t_agent_message_create` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='agent 消息总线（Redis Stream 仅作投递队列，本表为终态真相源）';

-- ------------------------------------------------------------
-- 2. 页面菜单：消息总线（3196，type=2，挂系统管理(1) 下；component 对应前端 views/system/bus/index.vue）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3196, '消息总线', 'bus', 2, 1, 2, '/system/bus', 'system/bus/index', 'ph:envelope-simple', 30, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3196);

-- ------------------------------------------------------------
-- 3. 按钮权限码（3197-3199，挂消息总线(3196) 下）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3197, '消息查询', 'bus:message:list', 3, 3196, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3197);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3198, '消息重投', 'bus:message:retry', 3, 3196, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3198);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3199, '手动发消息', 'bus:message:send', 3, 3196, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3199);

-- ------------------------------------------------------------
-- 4. 超级管理员（role_id=1）授权消息总线域（幂等补齐）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3196
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3196);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3197
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3197);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3198
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3198);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3199
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3199);
