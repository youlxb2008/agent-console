# agent-console 部署物料

> 单机 Docker Compose 部署：默认 bundled 一键全栈（backend + nginx + MySQL 8.0 + Redis 7，
> 首启自动建库灌种子）；外接自带库时只起 backend + nginx 两容器，行为与旧版一致。

## 文件

- `docker-compose.yml` — backend + nginx 两容器；另含 `bundled` profile 的 mysql/redis 服务（默认不启动）
- `compose-init/01-init.sh` — bundled 模式 MySQL 首启自动灌库入口（只读挂载进 /docker-entrypoint-initdb.d）
- `Dockerfile.backend` — jar → eclipse-temurin:17-jdk
- `nginx.conf` — 单端口双前端：`/` 门户 dist、`/admin` 管理端 dist、`/api` 反代 backend:8000
- `.env.example` — 环境变量模板（复制为 `.env` 后填写真实值；`.env` 不进 git）
- `sql/init-db.sh` — MySQL 全新库一键初始化（schema + 种子 + agent 域 + 菜单 SQL，全部幂等；外接库模式用）

## 两种模式

| 模式 | .env | 起的容器 | 数据库初始化 |
| --- | --- | --- | --- |
| bundled（一键全栈） | 保留 `COMPOSE_PROFILES=bundled` | backend + nginx + mysql + redis | 自动：MySQL 首启灌库（见下） |
| external（外接自带库） | 删掉 `COMPOSE_PROFILES` 行 | backend + nginx（与旧版一致） | 手动：`sql/init-db.sh`（见下） |

⚠️ bundled 的 mysql/redis 与 backend/nginx 之间没有 `depends_on`（depends_on 会反向自动
激活被依赖服务的 profile，把 external 模式也拖起 bundled 容器）。backend 就绪靠自身
`restart: unless-stopped`：MySQL 首启要先跑 entrypoint-initdb 灌库脚本再接受连接，
**首次启动 backend 可能自动重启 1-2 次等库就绪，属正常现象**，几十秒内自愈。

## 部署步骤（bundled 一键全栈）

```bash
# 0. 前置（一次性）：构建产物——backend.jar 与两个 dist 目录要先进 deploy/（见「构建产物」）
# 1. 克隆
git clone <this-repo> agent-console && cd agent-console/deploy
# 2. 准备 .env（模板默认已带 COMPOSE_PROFILES=bundled）
cp .env.example .env    # 至少改掉 AGC_MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD 两个 changeme
# 3. 一键起全栈（MySQL 首启自动建库+灌种子）
docker compose up -d --build
# 4. 打开
#    门户     http://localhost:36610    管理端 http://localhost:36610/admin
#    默认账号 admin / 123456（首次登录强制改密）
```

## 部署步骤（external 外接自带库）

```bash
# 1. 构建产物并放入 deploy/（同上「构建产物」）
# 2. cp .env.example .env；删掉 COMPOSE_PROFILES 行；AGC_MYSQL_HOST / AGC_REDIS_HOST
#    改回你的部署机地址
# 3. 手动初始化数据库（一次性；见下方「初始化数据库」）
# 4. docker compose up -d --build
# 5. 验收：curl http://localhost:36610/api/portal/site
```

## 初始化数据库

- **bundled 模式：自动。** mysql 容器首次启动（数据卷 `mysql-data` 为空）时，由
  `compose-init/01-init.sh` 按固定顺序自动灌库：schema → data → agent-domain →
  m13 → m2 → m35 → m4 → m71-menu（顺序红线见脚本头注释）。entrypoint-initdb 机制
  只在空数据卷执行一次，天然幂等，之后重启不重复灌。
- **external 模式：手动。** 用法：

```bash
cd deploy/sql
export MYSQL_HOST=<你的库地址> MYSQL_PORT=3306
export MYSQL_USER=agent-console MYSQL_PASSWORD=<密码>
./init-db.sh <database>    # 库不存在会自动创建（utf8mb4_general_ci）
```

脚本全部幂等，可重复执行；执行顺序与守卫说明见 `sql/init-db.sh` 头注释
（含 init 审计结论：哪些 m*.sql 增量无需进全新库序列及原因）。

## 构建产物

```bash
# 后端：mvn -f src/backend/pom.xml package（产物 backend.jar → 放 deploy/backend.jar）
# 前端：pnpm --dir src/frontend build && pnpm --dir src/portal build
#       （两个 dist 目录 → 放 deploy/dist/{frontend,portal}）
```

## 端口

- 对外默认 36610（`AGC_PORT` 可改）；backend 内部 8000；nginx 80；
  bundled 的 mysql/redis 仅 compose 内网可达，不发布宿主端口。
