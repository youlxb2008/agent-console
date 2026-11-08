-- ============================================================
-- 管理端 数据库 Schema
-- 兼容 H2（MODE=MySQL）和 MySQL 5.7+
-- 使用 CREATE TABLE IF NOT EXISTS 保证幂等：
--   - 全新数据库：建全部表
--   - 已有数据库：跳过已存在的表，不影响数据
--
-- 注意：H2 的约束名（UNIQUE KEY / KEY）是全局唯一的，
-- 因此所有约束名都带表名前缀（如 uk_t_role_code 而非 uk_code）
-- ============================================================

-- ------------------------------------------------------------
-- 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `name`                 VARCHAR(255),          -- 昵称（前端字段名 nickname）
    `username`             VARCHAR(255) NOT NULL, -- 登录名
    `password`             VARCHAR(255),          -- BCrypt 哈希
    `user_type`            INT,                   -- 0=超级管理员，1=普通用户
    `email`                VARCHAR(255),
    `phone`                VARCHAR(255),
    `enabled`              INT          DEFAULT 0,-- 0=正常，1=禁用（前端字段名 status）
    `avatar`               VARCHAR(255),
    `remark`               VARCHAR(255),
    `last_login_time`      DATETIME,
    `must_change_password` TINYINT      DEFAULT 0,-- 是否强制改密（首次登录）
    `created_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_t_user_username` (`username`)
);

-- ------------------------------------------------------------
-- 角色表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_role` (
    `id`         INT          NOT NULL AUTO_INCREMENT,
    `name`       VARCHAR(255) NOT NULL, -- 角色名称
    `code`       VARCHAR(255),          -- 角色编码（如 super_admin）
    `sort`       INT          DEFAULT 0,-- 排序
    `status`     INT          DEFAULT 0,-- 0=正常，1=禁用
    `remark`     VARCHAR(255),
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_t_role_code` (`code`)
);

-- ------------------------------------------------------------
-- 权限 / 菜单表
-- type: 1=目录, 2=菜单, 3=按钮, 4=API
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_permission` (
    `id`             INT          NOT NULL AUTO_INCREMENT,
    `name`           VARCHAR(255) NOT NULL,                 -- 名称
    `permission_key` VARCHAR(255),                          -- 权限标识（如 system:user）
    `type`           INT          NOT NULL,                 -- 类型
    `parent_id`      INT          DEFAULT 0,                -- 父节点 ID（0=根）
    `level`          INT          DEFAULT 1,                -- 层级（1=一级）
    `route_path`     VARCHAR(255),                          -- 前端路由路径
    `component`      VARCHAR(255),                          -- 前端组件路径
    `icon`           VARCHAR(255),                          -- 菜单图标
    `sort_order`     INT          DEFAULT 0,                -- 排序值
    `status`         INT          DEFAULT 1,                -- 1=启用，0=禁用
    `description`    VARCHAR(255),                          -- 描述
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_t_permission_parent_id` (`parent_id`)
);

-- ------------------------------------------------------------
-- 用户-角色关联表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user_role` (
    `id`         INT      NOT NULL AUTO_INCREMENT,
    `role_id`    INT      NOT NULL,
    `user_id`    BIGINT   NOT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_t_user_role_user_role` (`user_id`, `role_id`),
    KEY `idx_t_user_role_role_id` (`role_id`),
    KEY `idx_t_user_role_user_id` (`user_id`)
);

-- ------------------------------------------------------------
-- 角色-权限关联表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_role_permission` (
    `id`            INT      NOT NULL AUTO_INCREMENT,
    `role_id`       INT      NOT NULL,
    `permission_id` INT      NOT NULL,
    `created_at`    DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_t_role_perm_role_perm` (`role_id`, `permission_id`),
    KEY `idx_t_role_perm_permission_id` (`permission_id`)
);

-- ------------------------------------------------------------
-- 刷新令牌表（用于 JWT refresh）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_refresh_tokens` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       NOT NULL,
    `token_hash`  VARCHAR(255) NOT NULL,             -- token 哈希（快速查找）
    `device_name` VARCHAR(255),                      -- 设备名
    `device_id`   VARCHAR(255),                      -- 设备 ID
    `ip_address`  VARCHAR(255),                      -- 登录 IP
    `user_agent`  VARCHAR(512),                      -- UA（可能较长）
    `expires_at`  DATETIME,                           -- 过期时间
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `revoked_at`  DATETIME,                           -- 撤销时间（NULL=有效）
    PRIMARY KEY (`id`),
    KEY `idx_t_refresh_token_hash` (`token_hash`),
    KEY `idx_t_refresh_user_id` (`user_id`)
);

-- ------------------------------------------------------------
-- 站点配置表（key-value）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_option` (
    `id`           INT          NOT NULL AUTO_INCREMENT,
    `option_key`   VARCHAR(255) NOT NULL,
    `option_value` TEXT,                       -- 值可能较长（如 logo base64、JSON 配置）
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_t_option_key` (`option_key`)
);

-- ------------------------------------------------------------
-- 消息通知表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_notice` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `title`      VARCHAR(255) NOT NULL,
    `content`    TEXT,                         -- HTML 内容（大文本）
    `type`       INT,                          -- 1=系统通知，2=版本更新，3=维护公告
    `status`     INT,                          -- 1=已发布，0=草稿
    `publisher`  VARCHAR(255),                 -- 发布人用户名
    `remark`     VARCHAR(255),
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

-- ------------------------------------------------------------
-- 通知接收者表（定向通知，空表表示全员广播）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_notice_recipient` (
    `id`        BIGINT NOT NULL AUTO_INCREMENT,
    `notice_id` BIGINT NOT NULL,
    `user_id`   BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_t_notice_recipient_notice_user` (`notice_id`, `user_id`),
    KEY `idx_t_notice_recipient_user_id` (`user_id`)
);

-- ------------------------------------------------------------
-- 通知已读记录表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_notice_read` (
    `id`        BIGINT   NOT NULL AUTO_INCREMENT,
    `notice_id` BIGINT   NOT NULL,
    `user_id`   BIGINT   NOT NULL,
    `read_at`   DATETIME,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_t_notice_read_notice_user` (`notice_id`, `user_id`)
);

-- ------------------------------------------------------------
-- 定时任务表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_cron_job` (
    `id`            INT          NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(255) NOT NULL,
    `cron_expr`     VARCHAR(255) NOT NULL,              -- cron 表达式
    `handler_class` VARCHAR(255) NOT NULL,              -- 处理器标识
    `params`        TEXT,                               -- JSON 参数（大文本）
    `status`        INT          DEFAULT 0,             -- 0=禁用，1=启用
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

-- ------------------------------------------------------------
-- 定时任务执行日志表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_cron_log` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `job_id`     INT      NOT NULL,
    `status`     INT,                               -- 1=成功，0=失败
    `result`     TEXT,                               -- 执行结果（异常堆栈可能很长）
    `duration`   BIGINT,                             -- 耗时（毫秒）
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_t_cron_log_job_id` (`job_id`)
);

-- ------------------------------------------------------------
-- 审计日志表（系统域）
-- 命名 t_sys_audit_log：与 agent 域审计表（db/mysql/agent-domain.sql 的
-- t_audit_log，append-only）区分——两域审计模型字段不同，不可共用一张表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_sys_audit_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(255),                 -- 操作人用户名
    `user_id`     BIGINT,                       -- 操作人 ID
    `method`      VARCHAR(255),                 -- 请求方法
    `path`        VARCHAR(255),                 -- 请求路径
    `description` VARCHAR(255),                 -- 操作描述
    `params`      TEXT,                         -- 请求参数 JSON（大文本）
    `status`      INT,                          -- 响应状态码（保留字段）
    `duration`    BIGINT,                       -- 耗时（毫秒）
    `ip_address`  VARCHAR(255),                 -- 客户端 IP
    `success`     INT,                          -- 1=成功，0=失败
    `created_at`  DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_t_sys_audit_log_username` (`username`),
    KEY `idx_t_sys_audit_log_created_at` (`created_at`)
);
