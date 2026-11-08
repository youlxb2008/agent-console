-- ============================================================
-- agent 域数据模型（H2 MODE=MySQL + MySQL 5.7 双兼容）
-- 来源：docs/接口契约/data-model.md §2（列/类型/约束严格按契约 DDL）
-- 目标库：MySQL 5.7+，库 agent-console
--
-- 执行方式（2026-08-29 起）：
--   - dev/H2：SqlInitializer 启动时自动执行（幂等）
--   - MySQL 全新库：deploy/sql/init-db.sh 一键执行
--   - MySQL 已有库：重跑无副作用（全部语句带幂等守卫）
--
-- 方言说明（为 H2 兼容，与 MySQL 现网 DDL 差异仅限元数据）：
--   - 剥离 ENGINE=InnoDB / DEFAULT CHARSET / 表列 COMMENT（MySQL 方言，
--     H2 不保证兼容）；表/列注释以 -- 注释保留语义
--   - 列名/类型/约束与契约 DDL 完全一致；索引名带表前缀
--     （H2 约束名全局唯一，沿用 schema.sql 约定；MySQL 现网旧短名不受影响）
--   - JSON 类型 H2 2.x 原生支持；ON UPDATE CURRENT_TIMESTAMP 同样支持
--
-- 说明：
--   - 7 张表：t_agent / t_api_key / t_audit_log / t_note_meta / t_agent_resource
--     + ✨ t_notebook / t_agent_notebook（v3.5：独立笔记本实体 + agent↔笔记本 N:N）
--     （v3：笔记无主化 N:N，见 docs/版本/agent-v3_20260830；
--      t_note_meta 由本文件按 v3 结构创建（无 agent_id/file_path，增 dir_path）；
--      v3.5：t_note_meta 新增 notebook_id 列，t_notebook 独立内容容器、
--      t_agent_notebook 关联表——全新库无需再跑 notes-v2/v3.sql，迁移脚本仅存量库用）
--   - 【存量库升级】t_note_meta 的 notebook_id 列：本文件建表已含该列（全新库 H2/MySQL 自动有）；
--     已有生产库（MySQL 5.7）需用 deploy/sql/m35-menu.sql 幂等加列（动态 SQL，
--     H2 不支持 PREPARE/EXECUTE，故不放本文件）；已有 H2 dev 文件库请删库重建
--   - t_agent_resource 为通用关联表：resource_type 扩展位（note/chat/task/kb）
--   - t_audit_log 为 append-only，禁止 UPDATE/DELETE（代码层约束）
--   - 权限码 + 管理端菜单初始化 INSERT 沿用 data.sql 的幂等写法
--     （WHERE NOT EXISTS 守卫，已有数据跳过，不覆盖用户修改）
-- ============================================================

-- ------------------------------------------------------------
-- 2.1 t_agent（agent 档案）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,  -- 主键
  `name`         VARCHAR(64)  NOT NULL,                 -- agent 名（alpha/bravo/gamma/cron）
  `display_name` VARCHAR(128) NULL,                     -- 展示名（门户端显示）
  `avatar`       VARCHAR(255) NULL,                     -- 头像 URL（v4.3 新增；上传接口返回的 /uploads/... 相对路径，NULL=无头像走色块兜底）
  `description`  VARCHAR(512) NULL,                     -- 能力描述
  `status`       TINYINT      NOT NULL DEFAULT 1,       -- 1=active 0=suspended
  `created_by`   BIGINT       NOT NULL,                 -- 注册人（t_user.id）
  `approved_by`  BIGINT       NULL,                     -- 审批人（t_user.id）
  `approved_at`  DATETIME     NULL,                     -- 审批时间
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`   TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_agent_name` (`name`),
  KEY `idx_t_agent_status` (`status`)
);

-- ------------------------------------------------------------
-- 2.2 t_api_key（API Key + scopes）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_api_key` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT,
  `agent_id`     BIGINT      NOT NULL,                 -- 关联 t_agent.id
  `key_hash`     VARCHAR(64) NOT NULL,                 -- SHA-256(明文 key)
  `key_prefix`   VARCHAR(16) NOT NULL,                 -- 明文前缀（如 ac_demo_ab12），识别用
  `key_plain`    VARCHAR(128) NULL,                    -- 明文 key（v4.1 起落库供管理端随时复制；NULL=旧 key 未存明文，不可复制）
  `scopes`       JSON        NOT NULL,                 -- 权限列表，如 ["notes:write","audit:read"]
  `status`       TINYINT     NOT NULL DEFAULT 1,       -- 1=active 0=revoked
  `expires_at`   DATETIME    NULL,                     -- 过期时间，NULL=永久
  `last_used_at` DATETIME    NULL,                     -- 最后使用时间
  `revoked_by`   BIGINT      NULL,                     -- 作废人（t_user.id）
  `revoked_at`   DATETIME    NULL,
  `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`   TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_api_key_hash` (`key_hash`),
  KEY `idx_t_api_key_agent` (`agent_id`)
);

-- ------------------------------------------------------------
-- 2.3 t_audit_log（审计日志，append-only）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_audit_log` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `principal`   VARCHAR(64)  NOT NULL,  -- 操作主体（agent 名/用户名）
  `action`      VARCHAR(64)  NOT NULL,  -- key.create / key.revoke / note.write / note.submit / note.approve / note.reject / agent.create / auth.login / portal.read ...
  `resource`    VARCHAR(255) NULL,      -- 操作对象（note id / key id ...）
  `request`     JSON         NULL,      -- 请求摘要（method/path/参数，脱敏）
  `result`      VARCHAR(16)  NOT NULL,  -- success / denied / error
  `client_ip`   VARCHAR(64)  NULL,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_t_audit_log_principal_time` (`principal`, `create_time`),
  KEY `idx_t_audit_log_action_time` (`action`, `create_time`)
);

-- ------------------------------------------------------------
-- 2.4 t_note_meta（笔记索引表 v3/v3.5，真相源在文件系统 NOTES_ROOT/notes/{nid}.md）
--     v3：🔥 删 agent_id/file_path 列（无主化+扁平存储）；✨ 增 dir_path 列（树依据）
--     v3.5：✨ 增 notebook_id 列（笔记直接挂笔记本 D1，所属笔记本查询 WHERE notebook_id=X）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_note_meta` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,  -- 主键（纯内部，不外露）
  `nid`         VARCHAR(16)  NOT NULL,  -- 对外引用键 n_+10位base62，随机生成终身不变；覆写保留
  `notebook_id` BIGINT       NOT NULL,  -- 所属笔记本 t_notebook.id（v3.5 新增，D1）
  `dir_path`    VARCHAR(512) NOT NULL DEFAULT '',  -- 目录路径（树依据），如 工作日志/2026-08；根目录为空串
  `title`       VARCHAR(200) NOT NULL,  -- 笔记标题（管理端搜索字段）
  `tags`        VARCHAR(500) NULL,      -- 逗号分隔标签
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_note_meta_nid` (`nid`),
  KEY `idx_t_note_meta_update_time` (`update_time`),
  KEY `idx_t_note_meta_notebook` (`notebook_id`)  -- v3.5 新增：所属笔记本筛选
);

-- ------------------------------------------------------------
-- 2.4b ✨ t_note_chunk（知识库分块表：RAG 检索的向量/正文本地索引）
--     一行 = 一篇笔记的一个分块（~500 字/块，块间重叠约 60 字）：
--     - content  分块纯文本（BM25 展示与 RAG 上下文用；Lucene 全文索引另有文件目录）
--     - embedding 向量（float32[1024] 序列化，text-embedding-v3 默认 1024 维；NULL=尚未向量化）
--     - embed_model 向量化所用模型（换模型重建索引时的排查依据）
--     笔记覆写 = 先 DELETE 该 nid 全部块再插入（uk_nid_no 保证幂等）；
--     chunk 是派生数据（真相源仍是 md 文件），任何时刻可全量重建（管理端 rebuild 接口）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_note_chunk` (
  `id`          BIGINT         NOT NULL AUTO_INCREMENT,  -- 主键
  `nid`         VARCHAR(16)    NOT NULL,  -- 关联 t_note_meta.nid（逻辑外键）
  `notebook_id` BIGINT         NOT NULL,  -- 所属笔记本（冗余自 t_note_meta，检索范围过滤用）
  `chunk_no`    INT            NOT NULL DEFAULT 0,  -- 块序号（从 0 递增）
  `content`     VARCHAR(2048)  NOT NULL,  -- 分块文本（≤2000 字符）
  `embedding`   VARBINARY(8192) NULL,     -- float32 小端序列化（1024 维 = 4096 字节）
  `embed_model` VARCHAR(64)    NULL,      -- 向量模型名，如 text-embedding-v3
  `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_note_chunk_nid_no` (`nid`, `chunk_no`),
  KEY `idx_t_note_chunk_notebook` (`notebook_id`)
);

-- ------------------------------------------------------------
-- 2.5 ✨ t_agent_resource（agent 与资源 N:N 通用关联表，v3 新增）
--     resource_type 扩展位：note=笔记；未来 chat/task/kb 直接挂新 type，本表不再变更
--     纯关系行：无 update_time/is_deleted，解除关联=物理 DELETE（与 t_note_meta 物理删模型一致）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_resource` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `agent_id`      BIGINT      NOT NULL,                 -- 关联 t_agent.id（逻辑外键）
  `resource_type` VARCHAR(32) NOT NULL,                 -- 资源类型：note=笔记；预留 chat/task/kb
  `resource_id`   VARCHAR(64) NOT NULL,                 -- 资源外部键：note 填 nid（n_+10位base62）
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 关联时间
  `create_by`     VARCHAR(64) NULL,                     -- 关联操作人（管理员用户名/system-migration）
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_agent_resource` (`agent_id`, `resource_type`, `resource_id`),  -- 防重复关联，重复请求幂等忽略
  KEY `idx_t_agent_resource_res` (`resource_type`, `resource_id`)
);

-- ============================================================
-- agent 域权限码 + 管理端菜单初始化（v3 目标态）
-- 权限码清单：docs/接口契约/api.md §2 + 设计 agent-v3 §2.4
--   agent:agent:list/save/delete、agent:key:list/create/revoke、
--   agent:audit:list、agent:note:list/delete/link（v3 新增 link）
-- 菜单格式沿用 data.sql 现有写法（幂等 WHERE NOT EXISTS 守卫）
-- ID 段：3000/3050 目录 / 3010-3040 菜单 / 3100-3132 按钮（避开 管理端 1-202）
-- v3：3040 笔记列表迁至顶级目录 3050「笔记管理」下（route /note/list）
-- ============================================================

-- ------------------------------------------------------------
-- 一级目录
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3000, 'Agent 管理', 'agent', 1, 0, 1, '/agent', NULL, 'ph:robot', 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3000);

-- ✨ v3 新增一级目录：笔记管理（与 Agent 管理平级）
-- v3.5.1：同一入口征收 3040 笔记列表 + 3061 笔记本列表
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3050, '笔记管理', 'agent:note', 1, 0, 1, '/note', NULL, 'ph:note-pencil', 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3050);

-- ✨ v3.5.1 新增一级目录：日志管理（收纳审计日志等日志类子菜单）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3080, '日志管理', 'log', 1, 0, 1, '/log', NULL, 'ph:scroll', 6, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3080);

-- ------------------------------------------------------------
-- 二级菜单（Agent 管理下）
-- ------------------------------------------------------------
-- ⚠️ component 必须与前端 src/frontend/src/views/ 下的实际文件路径一致（不含 .vue 扩展名），
-- resolveComponent 按此路径动态加载组件，路径错误会导致页面渲染 404 兜底组件
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3010, 'Agent 列表', 'agent:agent', 2, 3000, 2, '/agent/agents', 'system/agent/index', 'ph:robot', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3010);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3020, 'API Key 管理', 'agent:key', 2, 3000, 2, '/agent/keys', 'system/agent/key', 'ph:key', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3020);

-- ✨ v3.5.1：审计日志迁至一级目录「日志管理」(3080) 下（route /log/audit）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3030, '审计日志', 'agent:audit', 2, 3080, 2, '/log/audit', 'system/agent/audit', 'ph:clipboard-text', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3030);

-- v3：3040 笔记列表迁至 3050 下（页面迁 views/system/note/index.vue，路由 /note/list）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3040, '笔记列表', 'agent:note', 2, 3050, 2, '/note/list', 'system/note/index', 'ph:note-pencil', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3040);

-- 已有 H2/dev 库可能残留 v2 旧行（parent=3000, route=/agent/notes），无条件纠正到目标态
-- （幂等：全新库该 UPDATE 影响 0 行；MySQL 存量库由 notes-v3.sql 负责，此处仅 H2 dev 兜底）
UPDATE `t_permission`
SET `name` = '笔记列表', `parent_id` = 3050, `level` = 2,
    `route_path` = '/note/list', `component` = 'system/note/index',
    `icon` = 'ph:note-pencil', `sort_order` = 1, `status` = 1
WHERE `id` = 3040;

-- ------------------------------------------------------------
-- 三级按钮（Agent 列表下）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3100, 'Agent 查询', 'agent:agent:list', 3, 3010, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3100);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3101, 'Agent 新增/编辑', 'agent:agent:save', 3, 3010, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3101);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3102, 'Agent 删除', 'agent:agent:delete', 3, 3010, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3102);

-- ------------------------------------------------------------
-- 三级按钮（API Key 管理下）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3110, 'Key 查询', 'agent:key:list', 3, 3020, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3110);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3111, 'Key 创建', 'agent:key:create', 3, 3020, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3111);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3112, 'Key 吊销', 'agent:key:revoke', 3, 3020, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3112);

-- ------------------------------------------------------------
-- 三级按钮（审计日志下）
-- v3.5.1 修正：3120 让位给 M4.2 台账管理一级目录（契约 §3 / m4-menu.sql），
--   审计按钮与生产 m13-menu.sql 对齐改用 3160，避免清库重建时 ID 冲突
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3160, '审计查询', 'agent:audit:list', 3, 3030, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3160);

-- ------------------------------------------------------------
-- 三级按钮（笔记列表下）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3130, '笔记查询', 'agent:note:list', 3, 3040, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3130);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3131, '笔记删除', 'agent:note:delete', 3, 3040, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3131);

-- ✨ v3 新增：关联维护（笔记↔agent 双向弹窗共用）
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3132, '关联维护', 'agent:note:link', 3, 3040, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3132);

-- ------------------------------------------------------------
-- 角色-权限关联
-- 超级管理员（role_id=1）：授予 agent 域全部权限
-- （data.sql 的自动同步只在首次写入执行，已有库不会追加，
--   故此处显式补齐，保证 agent 域权限对 super_admin 生效）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3000
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3000);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3010
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3010);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3020
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3020);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3030
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3030);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3040
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3040);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3050
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3050);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3100
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3100);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3101
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3101);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3102
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3102);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3110
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3110);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3111
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3111);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3112
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3112);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3120
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3120);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3130
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3130);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3131
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3131);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3132
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3132);
-- v3.5.1：审计按钮随菜单迁移 3120 → 3160（3120 让位 M4.2 台账目录）
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3160
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3160);

-- ============================================================
-- ===================== v3.5（独立笔记本实体）=====================
-- ============================================================
-- 设计：docs/接口契约/data-model.md §2.6/§2.7 + agent-v3.5_20260831
-- 新增表：t_notebook（独立内容容器，D4 软删）、t_agent_notebook（agent↔笔记本 N:N）
-- t_note_meta.notebook_id 列已随上面建表并入（全新库 H2/MySQL 自动有）；
--   存量 MySQL 生产库的幂等加列见 deploy/sql/m35-menu.sql（H2 不支持 PREPARE/EXECUTE，故不放本文件）
-- 全新库走本段菜单+权限种子；存量生产库走 deploy/sql/m35-menu.sql（ID 段一致，幂等不覆盖）

-- ------------------------------------------------------------
-- 2.6 ✨ t_notebook（v3.5 新增：独立笔记本实体）
--     name 未软删范围内唯一；软删 is_deleted=1 + name 改写 {name}__deleted_{id} 释放唯一键；
--     全链路查询一律 WHERE is_deleted=0（fail-closed, D4）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_notebook` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,  -- 主键
  `name`         VARCHAR(128) NOT NULL,                 -- 笔记本名（门户展示，未软删范围唯一）
  `description`  VARCHAR(512) NULL,                     -- 简介
  `cover`        VARCHAR(255) NULL,                     -- 封面 URL（可选，列表页横幅）
  `logo`         VARCHAR(512) NULL,                     -- logo URL（笔记页左上角小标识，方图）
  `hero`         VARCHAR(512) NULL,                     -- hero URL（笔记页空态中央展示图，宽幅）
  `status`       TINYINT      NOT NULL DEFAULT 1,       -- 1=active 0=hidden
  `created_by`   BIGINT       NOT NULL,                 -- 创建人（t_user.id）
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`   TINYINT      NOT NULL DEFAULT 0,       -- 软删标记（D4）
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_notebook_name` (`name`),
  KEY `idx_t_notebook_status` (`status`)
);

-- ------------------------------------------------------------
-- 2.7 ✨ t_agent_notebook（v3.5 新增：agent↔笔记本 N:N 关联表）
--     纯关系行：无 is_deleted/update_time，解除关联=物理 DELETE（D4）；
--     唯一键 (agent_id, notebook_id) 防重复关联，重复请求幂等忽略
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_notebook` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `agent_id`    BIGINT      NOT NULL,                   -- t_agent.id（逻辑外键）
  `notebook_id` BIGINT      NOT NULL,                   -- t_notebook.id（逻辑外键）
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 关联时间
  `create_by`   VARCHAR(64) NOT NULL,                   -- 操作人（管理员用户名/系统）
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_agent_notebook` (`agent_id`, `notebook_id`),
  KEY `idx_t_agent_notebook_notebook` (`notebook_id`)
);

-- ============================================================
-- notebook 域权限码 + 管理端菜单初始化（v3.5 目标态）
-- 权限码清单：docs/接口契约/api.md §2.6 + 技术方案 agent-v3.5 §3
--   notebook:list/create/edit/delete/agent:list/agent:link（驱动 /api/system/notebook/*）
-- 菜单格式沿用本文件上方写法（幂等 WHERE NOT EXISTS 守卫）
-- ID 段：3061 菜单 / 3170-3175 按钮 / 3080 日志目录（避开 管理端 1-202 与 agent 域 3000-3132）
-- v3.5.1 结构收敛：笔记与笔记本归同一入口 ——
--   一级目录「笔记管理」(3050) 下收纳：3040 笔记列表 + 3061 笔记本列表（原 3060 一级目录废弃删除）
-- ============================================================

-- ------------------------------------------------------------
-- 二级菜单（笔记管理 3050 下：笔记列表 / 笔记本列表）
-- ⚠️ component 必须与前端 src/frontend/src/views/system/notebook/index.vue 一致（不含扩展名）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3061, '笔记本列表', 'notebook', 2, 3050, 2, '/notebook/list', 'system/notebook/index', 'ph:book', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3061);

-- ------------------------------------------------------------
-- 三级按钮（笔记本列表下）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3170, '笔记本查询', 'notebook:list', 3, 3061, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3170);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3171, '笔记本新建', 'notebook:create', 3, 3061, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3171);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3172, '笔记本编辑', 'notebook:edit', 3, 3061, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3172);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3173, '笔记本删除', 'notebook:delete', 3, 3061, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3173);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3174, '关联 Agent 查询', 'notebook:agent:list', 3, 3061, 3, 5, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3174);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3175, '关联 Agent 维护', 'notebook:agent:link', 3, 3061, 3, 6, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3175);

-- ------------------------------------------------------------
-- 角色-权限关联（超级管理员 role_id=1）：授权 notebook 域全部
-- （沿本文件上方写法，WHERE NOT EXISTS 守卫幂等）
-- ------------------------------------------------------------
-- ✨ v3.5.1：日志管理一级目录授权（审计日志 3030 已挂其下）
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3080
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3080);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3061
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3061);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3170
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3170);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3171
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3171);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3172
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3172);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3173
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3173);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3174
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3174);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3175
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3175);

-- ------------------------------------------------------------
-- v3.5.1 菜单结构收敛（针对已存在旧结构的 H2 dev / 存量库，幂等）
-- 1) 3061 若已存在且挂在旧一级目录 3060 下 → 纠正到 3050（笔记管理）下
-- 2) 废弃 3060 一级目录「笔记本管理」→ 物理删除（尚无业务引用，level 1 目录无子数据依赖）
-- 3) 3030 审计日志若仍在 Agent 管理(3000)下 → 迁至日志管理(3080)下，路由改 /log/audit
-- ------------------------------------------------------------
UPDATE `t_permission`
SET `parent_id` = 3050, `level` = 2, `sort_order` = 2
WHERE `id` = 3061 AND `parent_id` <> 3050;

DELETE FROM `t_permission` WHERE `id` = 3060;
DELETE FROM `t_role_permission` WHERE `permission_id` = 3060;

UPDATE `t_permission`
SET `parent_id` = 3080, `level` = 2, `route_path` = '/log/audit', `sort_order` = 1
WHERE `id` = 3030 AND `parent_id` <> 3080;

-- ------------------------------------------------------------
-- 2.8 ✨ t_objective（目标，M4.1：每 agent 私域 todolist）
--     真相源在 OBJECTIVES_ROOT/agent-{id}/objective-{oid}.md（详情 md + checkbox 子项）
--     progress = 详情 md 子项 checkbox 解析 done/total*100，写接口时刷新
--     软删模型：is_deleted=1（@TableLogic），md 保留；全链路查询 is_deleted=0（fail-closed）
--     归属：agent_id 单值私域；创建时另写 t_agent_resource(type='objective') 作者关联行（D6 范式）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_objective` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,  -- 主键（纯内部，不外露）
  `oid`         VARCHAR(16)   NOT NULL,  -- 对外引用键 o_+10位base62，随机生成终身不变
  `agent_id`    BIGINT        NOT NULL,  -- 归属 agent（t_agent.id），每 agent 私域 todolist
  `title`       VARCHAR(200)  NOT NULL,  -- 目标标题（todolist 条目）
  `status`      TINYINT       NOT NULL DEFAULT 0,  -- 0未开始 1进行中 2已完成 3已放弃
  `period`      VARCHAR(20)   NULL,      -- 周期：2026-Q4 / 2026 / 自由文本
  `target_date` DATE          NULL,      -- 截止日期
  `progress`    DECIMAL(5,2)  NOT NULL DEFAULT 0,  -- 进度 0-100（详情 md 子项解析，写接口刷新）
  `body_path`   VARCHAR(500)  NOT NULL,  -- 详情 md 路径 OBJECTIVES_ROOT/agent-{id}/objective-{oid}.md
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`  TINYINT       NOT NULL DEFAULT 0,  -- 0未删除 1已删除（软删）
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_objective_oid` (`oid`),
  KEY `idx_t_objective_agent_status` (`agent_id`, `status`, `is_deleted`),
  KEY `idx_t_objective_target_date` (`target_date`)
);

-- ------------------------------------------------------------
-- 4.2 ✨ t_ledger（台账，M4.2：每 agent 私域流水 "我做过/想过/决定过什么"）
--     body 双轨制：≤500 字存 body_inline；>500 字写 LEDGER_ROOT/agent-{id}/ledger-{lid}.md + 存 body_path
--     列表/流接口不读文件（只回 title/type/时间/引用），详情接口读正文（inline 优先，否则读 md）
--     软删模型：is_deleted=1（@TableLogic），有 body_path 时文件保留不删
--     归属：agent_id 单值私域（每 agent 独立流水线）；无 update_time 列（流水按 create_time 排序）
--     5 种类型：progress进度 / observation观察 / decision决策 / memo备忘 / session会话小结
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_ledger` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,  -- 主键（纯内部，不外露）
  `lid`                VARCHAR(16)  NOT NULL,  -- 对外引用键 l_+10位base62，随机生成终身不变
  `agent_id`           BIGINT       NOT NULL,  -- 归属 agent（t_agent.id），每 agent 私域流水
  `type`               VARCHAR(20)  NOT NULL,  -- 类型：progress/observation/decision/memo/session
  `title`              VARCHAR(200) NOT NULL,  -- 条目标题
  `body_inline`        TEXT         NULL,      -- 短正文（≤500 字直接存库；>500 字为 NULL）
  `body_path`          VARCHAR(500) NULL,      -- 长正文 md 路径 LEDGER_ROOT/agent-{id}/ledger-{lid}.md
  `ref_task_id`        VARCHAR(16)  NULL,      -- 关联任务 tid（可选，事件归因引用列，可并存）
  `ref_requirement_id` VARCHAR(16)  NULL,      -- 关联需求 rid（可选）
  `ref_objective_id`   VARCHAR(16)  NULL,      -- 关联目标 oid（可选）
  `ref_note_nid`       VARCHAR(16)  NULL,      -- 关联笔记 nid（可选）
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 流水时间（记账时刻）
  `is_deleted`         TINYINT      NOT NULL DEFAULT 0,  -- 0未删除 1已删除（软删）
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_ledger_lid` (`lid`),
  KEY `idx_t_ledger_agent_time` (`agent_id`, `create_time`, `is_deleted`),
  KEY `idx_t_ledger_type` (`type`, `is_deleted`),
  KEY `idx_t_ledger_ref_task` (`ref_task_id`),
  KEY `idx_t_ledger_ref_req` (`ref_requirement_id`)
);

-- ============================================================
-- ===================== M4 菜单（目标/台账管理端）=====================
-- ============================================================
-- 契约：docs/版本/agent-v4_20260831/agent-v4_20260831-契约定稿.md §3
-- 对应生产库脚本：deploy/sql/m4-menu.sql（MySQL；H2 dev 走本段，写法同源幂等）
-- ⚠️ MR !45 教训：菜单必须三件套齐全！
--    目录(type=1) + 页面菜单(type=2 带 component) + 按钮(type=3 挂 type=2 下)。
--    缺 type=2 页面菜单 = 侧边栏不显示、路由不注册（点击 404）。
-- ID 段：3090/3091 目标目录+页面、3120/3121 台账目录+页面、
--        按钮 3180-3183（objective:*）/ 3184-3187（ledger:*）
-- ------------------------------------------------------------
-- 存量 H2 dev 修正：审计按钮曾占用 3120（m4 之前），让位台账目录。
-- ⚠️ H2 2.x 禁止 UPDATE 自增主键列（id 为 AUTO_INCREMENT/IDENTITY），
--    故不能用 UPDATE id 3120→3160，改为「删旧授权行 + 删旧按钮行」：
--    - 新按钮 3160 及其授权已由本文件上方段落幂等插入，无需重插；
--    - 仅当 3120 是旧审计按钮时才删（幂等：全新库 3120 不存在、
--      已迁移库 3120 是台账目录，均影响 0 行）
-- ------------------------------------------------------------
-- 1) 先删旧按钮的角色授权行（role_id=1 → 3120 旧审计授权）
DELETE FROM `t_role_permission`
WHERE `permission_id` = 3120
  AND EXISTS (SELECT 1 FROM `t_permission`
              WHERE `id` = 3120 AND `permission_key` = 'agent:audit:list');
-- 2) 再删旧审计按钮行本身（释放 3120 给下方台账目录使用）
DELETE FROM `t_permission`
WHERE `id` = 3120 AND `permission_key` = 'agent:audit:list';

-- ------------------------------------------------------------
-- 1. 目标管理（M4.1）：目录 3090 + 页面 3091 + 按钮 3180-3183
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3090, '目标管理', 'objective', 1, 0, 1, '/objective', NULL, 'ph:target', 7, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3090);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3091, '目标列表', 'objective', 2, 3090, 2, '/objective/list', 'system/objective/index', 'ph:target', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3091);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3180, '目标查询', 'objective:list', 3, 3091, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3180);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3181, '目标新建', 'objective:create', 3, 3091, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3181);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3182, '目标编辑', 'objective:edit', 3, 3091, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3182);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3183, '目标删除', 'objective:delete', 3, 3091, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3183);

-- ------------------------------------------------------------
-- 2. 台账管理（M4.2）：目录 3120 + 页面 3121 + 按钮 3184-3187
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3120, '台账管理', 'ledger', 1, 0, 1, '/ledger', NULL, 'ph:notebook', 8, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3120);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3121, '台账列表', 'ledger', 2, 3120, 2, '/ledger/list', 'system/ledger/index', 'ph:notebook', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3121);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3184, '台账查询', 'ledger:list', 3, 3121, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3184);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3185, '台账新建', 'ledger:create', 3, 3121, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3185);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3186, '台账编辑', 'ledger:edit', 3, 3121, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3186);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3187, '台账删除', 'ledger:delete', 3, 3121, 3, 4, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3187);

-- ------------------------------------------------------------
-- 3. 超级管理员（role_id=1）授权 M4 菜单 + 迁移后的审计按钮（幂等补齐）
-- ------------------------------------------------------------
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3090
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3090);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3091
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3091);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3180
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3180);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3181
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3181);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3182
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3182);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3183
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3183);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3120
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3120);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3121
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3121);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3184
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3184);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3185
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3185);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3186
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3186);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3187
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3187);

-- ------------------------------------------------------------
-- 5.2 ✨ t_todo（待办，M5.2：agent 私域 todolist，调研池迁入）
--     真相源即本行（content MEDIUMTEXT 直接入库，无文件系统）
--     todo_id：d_ + 10 位 base62（对齐 n_/o_/l_ 惯例），随机生成终身不变
--     排序契约：(due_date IS NULL) ASC, due_date ASC, priority DESC, id ASC
--               （M5.3：有截止日期在前按日期升序，无日期按优先级排后）
--     软删模型：is_deleted=1（@TableLogic），全链路查询 is_deleted=0（fail-closed）
--     归属：agent_id 单值私域；越权（操作非本人）→ 403，不存在/已软删 → 404
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_todo` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,  -- 主键（纯内部，不外露）
  `todo_id`     VARCHAR(20)  NOT NULL,  -- 对外引用键 d_+10位base62，随机生成终身不变
  `agent_id`    BIGINT       NOT NULL,  -- 归属 agent（t_agent.id），每 agent 私域 todolist
  `title`       VARCHAR(200) NOT NULL,  -- 标题（≤200 字符）
  `content`     MEDIUMTEXT   NULL,      -- md 正文：来源/原因/产出要求（直接入库）
  `status`      TINYINT      NOT NULL DEFAULT 0,  -- 0待办 1进行中 2已完成 3已放弃（对齐 objective 状态机）
  `priority`    TINYINT      NOT NULL DEFAULT 3,  -- 优先级 1-5（默认 3）
  `tags`        VARCHAR(200) NULL,      -- 逗号分隔字符串（对齐笔记 tags 惯例）
  `due_date`    DATE         NULL,      -- 截止日期（可空；有值排前，过期标红）
  `create_time` DATETIME     NULL,
  `update_time` DATETIME     NULL,
  `is_deleted`  TINYINT      NOT NULL DEFAULT 0,  -- 0未删除 1已删除（软删）
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_todo_todo_id` (`todo_id`),
  KEY `idx_t_todo_agent_status` (`agent_id`, `is_deleted`, `status`),
  KEY `idx_t_todo_due_date` (`due_date`)
);

-- ------------------------------------------------------------
-- 5.3 ✨ t_activity（agent 实时活动流，M6.1）
--     纯流水表：只追加不修改不软删（无 is_deleted/update_time），
--     保留策略：ActivityCleanupJob 每日 03:30（Asia/Shanghai）物理清理 30 天前数据
--     归属：agent_id 由上报接口从 key 身份（AgentContext）注入，不信任请求体
--     事件类型白名单：turn_started/status_changed/tool_call/turn_completed
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_activity` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` BIGINT NOT NULL COMMENT '关联 t_agent.id',
  `session_key` VARCHAR(128) DEFAULT NULL COMMENT '来源会话 key',
  `event_type` VARCHAR(32) NOT NULL COMMENT 'turn_started/status_changed/tool_call/turn_completed',
  `tool_name` VARCHAR(64) DEFAULT NULL COMMENT '工具名（tool_call 时有值）',
  `summary` VARCHAR(512) DEFAULT NULL COMMENT '摘要（服务端截断兜底）',
  `duration_ms` INT DEFAULT NULL COMMENT '耗时（tool_call/turn_completed）',
  `outcome` VARCHAR(16) DEFAULT NULL COMMENT 'completed/failed/interrupted（turn_completed）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_activity_agent_time` (`agent_id`, `create_time`),
  KEY `idx_activity_session` (`session_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='agent 实时活动流';

-- ------------------------------------------------------------
-- 5.4 ✨ t_memory（agent 长期记忆，M7.1：记忆与遗忘模块）
--     结构化记忆条目：memType 五类（fact/decision/preference/lesson/event）+
--     source 三源（dialogue/self/system）；title 一句话摘要 + content_md 结论正文
--     生命周期：status 1=active / 2=archived / 3=superseded；
--               superseded_by 指向取代本条的新记忆 id（链式，详情接口沿链回溯最新版）
--     衰减元数据：weight（memoryDecayJob 全量重算）、access_count/last_accessed_at
--               （检索命中即刷新）、confirmed_at（confirm 接口刷新）
--     对外引用键：数字 id 自增即对外键（API 路径 /memories/{id}，与 todo 的 d_ 键刻意不同）
--     软删模型：is_deleted=1（@TableLogic），全链路查询 is_deleted=0（fail-closed）
--     归属：agent_id 单值私域；跨 agent 访问一律 404（不泄露存在性）
--     Lucene 独立索引：memory.index-root（默认 {data-root}/memory-index），
--               字段 title/tags/content_md；归档/被取代参与检索带 status 标记
-- ------------------------------------------------------------
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
  `weight_floor`     INT          NOT NULL DEFAULT 0 COMMENT '权重保底线（0=不保底，M7.1-M3a）',
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

-- ------------------------------------------------------------
-- 5.5 t_agent_message（agent 消息总线，M1：bus-m1）
--     Redis Stream 仅作投递队列（ac:agent-bus:stream:{toAgentId}，按目标分片），
--     本表为终态真相源；status 状态机 0待投递/1已送达(协议层)/2已读(M2)/3失败；
--     message_id 幂等键（服务端 UUID）；from 由 X-API-Key 推导（body 不可指定，防伪造）；
--     session_key 强制 bus-* 命名空间（投递后回填实际会话）；model 须经目标路由白名单校验
--     索引名带表前缀（H2 约束名全局唯一）；存量 MySQL 库增量脚本见 deploy/sql/m427-bus-m1.sql
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
  `expect_reply_minutes` INT    NULL COMMENT '消息级回音时限（分钟，1~1440）；NULL=走全局默认 BUS_FUSE_DEFAULT_MINUTES（bus-watchdog-fuse）',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待投递/1已送达(协议层)/2已读(M2)/3失败',
  `channel`       VARCHAR(8)   NULL COMMENT '实际投递通道：ws/api（dispatcher 投递成功回填；api 缺 BUS_API_TARGET 配置降级 ws 时也写 ws）；历史行 NULL 前端显「—」（bus-ui-v2）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='agent 消息总线';

-- ------------------------------------------------------------
-- 5.6 t_bus_topic_watch（总线超时保险丝等待段状态，bus-watchdog-fuse）
--     保险丝扫描器维护：topic 等待段（最后一条消息与线程首条同向且超时限无回音）
--     告警去重——alerted_message_id 记当前等待段锚点（末条消息 id，恒非空，同一等待段
--     只告警一次）；同 topic 出现任何新消息即新等待段可再告警；alert_count=0 表示
--     发现时 BUS_ALERT_SESSION 未配置仅 warn；等待段结束（回音/系统消息收尾/自投线程）
--     删行销案。存量 MySQL 库增量脚本见 deploy/sql/m428-bus-watchdog-fuse.sql
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_bus_topic_watch` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic`              VARCHAR(100) NOT NULL COMMENT '原始 topic（无 [fuse] 前缀），唯一',
  `alerted_message_id` BIGINT       NOT NULL COMMENT '当前等待段锚点（末条消息 id，恒非空；同段只告警一次，新段可再告警）',
  `alert_count`        INT          NOT NULL DEFAULT 0 COMMENT '该 topic 累计告警次数',
  `last_alert_at`      DATETIME     NULL COMMENT '最近一次告警时间',
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_t_bus_topic_watch_topic` (`topic`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='总线超时保险丝等待段状态（bus-watchdog-fuse）';

-- ------------------------------------------------------------
-- 5.7 t_bus_route（总线投递通道方向级路由，bus-route-channel）
--     按方向（from agent -> to agent）配置投递通道 ws/api；无配置行 = ws（向后兼容，
--     存量行为不变）；from = to 校验拒绝（自投不参与路由）；
--     保存即时生效（dispatcher 投递前现查不缓存）。
--     存量 MySQL 库增量脚本见 deploy/sql/m429-bus-route.sql
-- ------------------------------------------------------------
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

-- ------------------------------------------------------------
-- 消息总线权限/菜单（M1）
-- 3196 页面菜单「消息总线」（挂系统管理(1) 下，component=system/bus/index）
-- 3197-3199 按钮：bus:message:list / retry / send
-- id 段续接记忆域 3190-3194（执行/执行前先查占用，种子漂移常态）
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3196, '消息总线', 'bus', 2, 1, 2, '/system/bus', 'system/bus/index', 'ph:envelope-simple', 30, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3196);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3197, '消息查询', 'bus:message:list', 3, 3196, 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3197);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3198, '消息重投', 'bus:message:retry', 3, 3196, 3, 2, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3198);

INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `sort_order`, `status`)
SELECT 3199, '手动发消息', 'bus:message:send', 3, 3196, 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3199);

INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3196
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3196);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3197
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3197);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3198
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3198);
INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3199
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3199);

-- ------------------------------------------------------------
-- 总线调试（v4.37 bus-sessionkey-fix）
-- 3200 页面菜单「总线调试」（挂系统管理(1) 下，component=system/bus/debug）
-- 权限点复用 bus:message:list / bus:message:send（3197/3199），不新增按钮行
-- id 执行/合并前先查占用（种子漂移常态）；deploy/sql/m437-bus-debug-menu.sql 为存量库增量
-- ------------------------------------------------------------
INSERT INTO `t_permission` (`id`, `name`, `permission_key`, `type`, `parent_id`, `level`, `route_path`, `component`, `icon`, `sort_order`, `status`)
SELECT 3200, '总线调试', 'bus:debug', 2, 1, 2, '/system/bus-debug', 'system/bus/debug', 'ph:bug', 31, 1
WHERE NOT EXISTS (SELECT 1 FROM `t_permission` WHERE `id` = 3200);

INSERT INTO `t_role_permission` (`role_id`, `permission_id`) SELECT 1, 3200
WHERE NOT EXISTS (SELECT 1 FROM `t_role_permission` WHERE `role_id` = 1 AND `permission_id` = 3200);
