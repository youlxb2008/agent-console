#!/usr/bin/env bash
# ============================================================================
# agent-console bundled MySQL 首启自动灌库入口
#
# 由 deploy/docker-compose.yml 只读挂载到 /docker-entrypoint-initdb.d/01-init.sh，
# 在 mysql 官方镜像的 entrypoint-initdb 阶段执行；该阶段只在数据卷
# （named volume mysql-data）为空的首次启动跑一次，之后不再执行——天然幂等。
#
# 执行顺序红线（勿调换）：
#   1. schema.sql        基础管理端表（IF NOT EXISTS）
#   2. data.sql          基础种子 + 超管权限自动同步（守卫=「role_id=1 无任何权限行」，
#                        必须先于 agent 域权限段执行）
#   3. agent-domain.sql  agent 域 + notebook 域表与权限（agent 域权限 3000-3200）
#   4. m13-menu.sql      M1.3 菜单 component 修正
#   5. m2-menu.sql       M2.0 笔记菜单改名/权限码修正
#   6. m35-menu.sql      M3.5 笔记本菜单/权限 + t_note_meta.notebook_id 加列（守卫动态 SQL）
#   7. m4-menu.sql       M4.1 目标域菜单/权限
#   8. m71-menu.sql      M7.1 记忆域菜单/权限（3190-3194）+ memoryDecayJob cron 种子
#                        —— init 审计结论：这是「schema+data+agent-domain」并集之外
#                        唯一有新增对象的增量（其余 m*.sql 审计矩阵见 init-db.sh 头注释
#                        与交付笔记）
#
# ⚠️ entrypoint-initdb 阶段的连接没有默认库，所有 SQL 必须显式选库
#    （本脚本经 mysql 客户端位置参数指定库名），裸执行会报 No database selected。
# ============================================================================
set -euo pipefail

DB="${MYSQL_DATABASE:?MYSQL_DATABASE is not set}"
SQL_DIR="${AGC_SQL_DIR:-/opt/agc-sql}"
ORDER=(01-schema.sql 02-data.sql 03-agent-domain.sql 04-m13-menu.sql 05-m2-menu.sql \
       06-m35-menu.sql 07-m4-menu.sql 08-m71-menu.sql)

# entrypoint-initdb 阶段 root 的密码状态随镜像小版本有差异（部分版本在
# docker_setup_db 之后、部分在临时服务器 init-file 阶段设置）：优先带
# MYSQL_ROOT_PASSWORD 走 socket 认证，连不上再回落免密连接。
client() {
  if [ -n "${MYSQL_ROOT_PASSWORD:-}" ] && \
     MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot -hlocalhost \
       -e 'SELECT 1' >/dev/null 2>&1; then
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot -hlocalhost "$@"
  else
    mysql --protocol=socket -uroot -hlocalhost "$@"
  fi
}

echo "[agc-init] database=$DB seeding ${#ORDER[@]} sql files in fixed order"
for f in "${ORDER[@]}"; do
  p="$SQL_DIR/$f"
  if [ ! -f "$p" ]; then
    echo "[agc-init] ERROR: missing $p (compose 挂载不齐，检查 docker-compose.yml volumes)" >&2
    exit 1
  fi
  echo "[agc-init] >>> $f"
  # 显式选库：库名走 mysql 客户端位置参数（库名含连字符也安全）
  client --default-character-set=utf8mb4 "$DB" < "$p"
done

# 灌库结果自检（行数打进首启日志，便于肉眼核对）
client -N --default-character-set=utf8mb4 -e \
  "SELECT CONCAT('[agc-init] t_user rows = ', COUNT(*)) FROM \`$DB\`.t_user;
   SELECT CONCAT('[agc-init] t_permission rows = ', COUNT(*)) FROM \`$DB\`.t_permission;
   SELECT CONCAT('[agc-init] t_cron_job rows = ', COUNT(*)) FROM \`$DB\`.t_cron_job;"
echo "[agc-init] done."
