#!/usr/bin/env bash
# ============================================================
# agent-console MySQL 全新库一键初始化脚本
#
# 用法：
#   export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306
#   export MYSQL_USER=agent-console MYSQL_PASSWORD=xxx
#   ./init-db.sh <database>
#
# 执行顺序（敏感，勿调换）：
#   1. schema.sql        基础表（IF NOT EXISTS 幂等）
#   2. data.sql          基础种子（WHERE NOT EXISTS 幂等）
#   3. agent-domain.sql  agent 域 + notebook 域表与权限（幂等，含 t_notebook/t_agent_notebook）
#   4. m13-menu.sql      M1.3 菜单 component 修正（幂等）
#   5. m2-menu.sql       M2.0 笔记菜单改名/权限码修正（幂等）
#   6. m35-menu.sql      M3.5 笔记本菜单/权限 + t_note_meta 加列（幂等，MySQL动态SQL）
#   7. m4-menu.sql       M4.1 目标域菜单/权限（幂等）
#   8. m71-menu.sql      M7.1 记忆域菜单/权限 3190-3194 + memoryDecayJob cron 种子（幂等）
#   顺序原因：data.sql 超管权限自动同步的守卫是 "role_id=1 无任何权限行"，
#   必须先同步基础权限，agent-domain.sql 再补 agent 域权限（3000-3200）。
#
# 幂等性：全部脚本可重复执行，已有库重跑不覆盖任何数据。
#
# init 审计结论（2026-11-08，逐文件核对 deploy/sql/*.sql × schema/data/agent-domain
# 并集）：仅 m71-menu.sql 含并集之外的新增对象（权限 3190-3194 + memoryDecayJob
# cron 行），故只追加它；其余增量文件均为并集内对象的幂等 no-op，不进全新库
# 初始化序列：
#   - m417-notebook-images（t_notebook.logo/hero 列已在 agent-domain.sql 建表内）
#   - m427-bus-m1（t_agent_message IF NOT EXISTS + 菜单 3196-3199 已在 agent-domain.sql）
#   - m428-bus-watchdog-fuse（t_bus_topic_watch IF NOT EXISTS + expect_reply_minutes 列已在）
#   - m429-bus-route（t_bus_route IF NOT EXISTS 已在）
#   - m430-bus-ui-v2-channel（t_agent_message.channel 列已在）
#   - m437-bus-debug-menu（菜单 3200 已在 agent-domain.sql）
#   - m71-memory（t_memory IF NOT EXISTS 已在）
#   - m713-weight-floor（t_memory.weight_floor 列已在）
#   存量库升级如需回放历史迁移，仍可手工逐个执行上述文件（全部幂等，跑了也不冲突）。
#
# 注意：不含 notes-v2.sql / notes-v3.sql —— 两者含 DROP TABLE / 无守卫 CREATE
#   （t_note / t_note_review / t_note_meta），属存量库迁移专用；全新库最终形态
#   已由 agent-domain.sql 一步建齐。
# ============================================================
set -euo pipefail

DB="${1:?用法: $0 <database>}"
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_USER:?需 export MYSQL_USER}"
: "${MYSQL_PASSWORD:?需 export MYSQL_PASSWORD}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RES="$REPO_ROOT/src/backend/src/main/resources"

# 密码走 MYSQL_PWD 环境变量（避免命令行参数在 ps 中暴露）
export MYSQL_PWD="$MYSQL_PASSWORD"
MYSQL_CMD=(mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" --default-character-set=utf8mb4)

run_sql() {
  local label="$1" file="$2"
  echo ">>> [$label] $file"
  "${MYSQL_CMD[@]}" "$DB" < "$file"
  echo "    OK"
}

echo "=== agent-console 数据库初始化: $DB @ $MYSQL_HOST:$MYSQL_PORT ==="

# 库不存在则创建（utf8mb4；已有库不受影响）
echo ">>> [0/8] 确保数据库存在: $DB"
"${MYSQL_CMD[@]}" -e "CREATE DATABASE IF NOT EXISTS \`$DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

run_sql "1/8 基础表"                       "$RES/schema.sql"
run_sql "2/8 种子数据"                     "$RES/data.sql"
run_sql "3/8 agent 域+notebook 域表与权限" "$RES/db/mysql/agent-domain.sql"
run_sql "4/8 M1.3 菜单修正"                "$SCRIPT_DIR/m13-menu.sql"
run_sql "5/8 M2.0 笔记菜单修正"            "$SCRIPT_DIR/m2-menu.sql"
run_sql "6/8 M3.5 笔记本菜单+加列"         "$SCRIPT_DIR/m35-menu.sql"
run_sql "7/8 M4.1 目标菜单"                "$SCRIPT_DIR/m4-menu.sql"
run_sql "8/8 M7.1 记忆菜单+cron 种子"      "$SCRIPT_DIR/m71-menu.sql"

echo "=== 全部完成 ==="
