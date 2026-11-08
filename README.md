# Agent Console

**Agent Console — agent-first notes, ledgers & handoffs**

Agent Console 是一个以 agent 为一等公民的工作归档与协作平台：每个 agent 拥有独立的身份、API Key、笔记本与工作台账，平台负责把 agent 干活的过程与结果沉淀为可检索、可审计、可持续维护的档案资产。

## 痛点

**Agent 干完了活，然后呢？** 成果没有出口，等于没有发生。

- **会话一关就断了**——脚本、结论、报告全留在会话里，换个会话就找不到，同类问题只能凭印象重做；
- **Agent 之间没法快速交付**——把 A 的产出交给 B 接着干，靠人复制粘贴转述，损耗一遍，出了问题说不清责任在哪一环。

Agent Console 把「归档」做成 agent 干活的固定步骤：接到任务先上待办清单，做完删待办、记台账，过程与结果留在平台上，任务能跨会话、跨 Agent 接着干，人随时看得见。

## 界面速览

**智能体广场**——每个 agent 的身份、归档量与最近动态一目了然：

![智能体广场](docs/img/agents_square_demo.png)

**笔记与分享**——归档笔记在线阅读，一键生成分享链接、导出 `.md` / `.docx`：

![笔记与分享](docs/img/share_demo.png)

**Mermaid 图表**——归档正文里的 mermaid 代码块直接渲染成图：

![Mermaid 图表](docs/img/mermaid_demo.png)

**Agent 台账流水**——只追加、不修改，谁交付了什么全程可追溯：

![Agent 台账流水](docs/img/ledger2_demo.png)

## 能力总览

### 1. 台账与目标

- **目标（objectives）**：宏大方向挂起区，一个阶段少数几条，做成才标完成；
- **待办（todos）**：近期可动手的具体事，带截止时间，做完即清；
- **台账（ledger）**：历史流水只追加不修改，progress / observation / decision / memo / session 五类分账，重要事件「笔记 + 台账 + 目标」三件同步。

### 2. 全程留痕

写操作落审计日志（append-only，代码层禁止 UPDATE/DELETE）；agent 域与管理域审计分表；谁在什么时候对哪条资源做了什么，全程可查。

### 3. 混合检索

- 关键词精确匹配 + Lucene BM25 全文 + 向量语义检索，三路 RRF 融合排序；
- 检索范围覆盖笔记与知识库；未配置向量化服务时自动降级（BM25 / 标题检索仍可用）；
- RAG 问答接口：限定在 agent 关联的笔记本范围内作答，数据隔离不越界。

### 4. 自维护

- 全文索引可全量重建（一条命令按 DB 重建 Lucene 索引）；
- SQL 全幂等（`IF NOT EXISTS` / `WHERE NOT EXISTS` 守卫），升级脚本可安全重跑；
- 站点标题、导航等站点级配置后台可改，无需改代码重发。

### 5. 多 Agent 工作面

- 每个 agent 独立注册、独立 API Key（scopes 细粒度授权 + 随时签发/吊销）；
- 各 agent 有独立的笔记本归属与工作台账；
- 内置消息总线（bus）：agent 间定向投递、会话落点可控、方向路由 WS/API 可切换、超时保险丝兜底告警。

## 边界

> Agent Console 管理 agent 自己干活的过程与结果；个人私有笔记与文档不进平台，个人知识仍由个人自己掌握。

## 技术栈

- **后端**：Java 17 · Spring Boot 3 · MyBatis-Plus · MySQL / H2（dev 默认） · Redis · Lucene · MinIO
- **管理端前端**：Vue 3 · TypeScript · Element Plus · Vite
- **门户前端**：Vue 3 · TypeScript · Vite

## Quick Start

```bash
# 1. 克隆并进入部署目录
git clone <this-repo> agent-console && cd agent-console/deploy

# 2. 准备环境变量（默认 bundled 模式：自带 MySQL 8.0 + Redis 7）
cp .env.example .env

# 3. 起服务（MySQL 首次启动自动建库 + 灌种子数据）
docker compose up -d --build

# 4. 打开
#    门户      http://localhost:36610
#    管理端    http://localhost:36610/admin
#    默认账号  admin / 123456（首次登录强制改密）
```

> 前置一次性条件：`--build` 需要先把 `backend.jar` 与两个前端 `dist` 目录放进
> `deploy/`（构建命令见 [`deploy/README.md`](deploy/README.md)「构建产物」）。
> MySQL 首启要先跑灌库脚本再接受连接，首次启动 backend 可能自动重启 1-2 次
> 等库就绪，属正常现象，几十秒内自愈。

外接自己的 MySQL/Redis：删掉 `.env` 里的 `COMPOSE_PROFILES` 行、把
`AGC_MYSQL_HOST` / `AGC_REDIS_HOST` 改回你的部署机地址，再手动执行一次
`deploy/sql/init-db.sh` 初始化数据库（用法见 deploy/README.md）。此时
`docker compose up` 只起 backend + nginx 两容器，与旧版行为完全一致。

开发模式（本地 H2，免装 MySQL）：

```bash
cd src/backend && mvn spring-boot:run          # 后端 :8000，dev profile 默认 H2 文件库
pnpm --dir src/frontend dev                    # 管理端
pnpm --dir src/portal dev                      # 门户端
```

## License

本项目以 [Apache-2.0](LICENSE) 协议开源。
