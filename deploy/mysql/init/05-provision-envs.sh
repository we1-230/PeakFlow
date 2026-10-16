#!/bin/bash
# ============================================================================
# PeakFlow 秒杀商城 · 把 01~03 灌进 test / prod 两套环境
# ----------------------------------------------------------------------------
# 执行时机：MySQL 官方镜像在【数据卷首次初始化】时，按文件名字典序执行
#           /docker-entrypoint-initdb.d/ 下的脚本。本文件以 05- 开头，
#           排在 01~04 之后，因此可安全复用 01~03 这几个 dump 文件。
#
# 为什么需要它：
#   01~04 是 mysqldump 产物，内部没有 USE 语句，只能写入「当前默认库」。
#   而容器启动时 MYSQL_DATABASE=peakflow_dev，所以 01~04 自然落在 dev 库。
#   test / prod 需要再各灌一遍，就在这里显式指定目标库。
#
# 各环境灌什么（与 deploy/mysql/README.md 的场景表一致）：
#   peakflow_dev   01 + 02 + 03 + 04   （已由 01~04 自动完成）
#   peakflow_test  01 + 02 + 03        （集成测试要 role_id=2 有 user:manage，故必须有 02）
#   peakflow_prod  01 + 02 + 03        （不含演示账号；管理员用 prod/grant-admin-role.sql 授权）
#
# 幂等性：01-schema.sql 每张表前都有 DROP TABLE IF EXISTS，重复执行安全。
# ============================================================================
set -euo pipefail

INIT_DIR="/docker-entrypoint-initdb.d"
DB_USER="root"
DB_PASS="${MYSQL_ROOT_PASSWORD:-}"

log() { echo "[peakflow-init] $*"; }

# 以指定库为默认库执行一个 sql 文件
run_sql() {
    local db="$1"
    local file="$2"
    log "  灌入 ${db} <- $(basename "$file")"
    mysql --user="$DB_USER" --password="$DB_PASS" --database="$db" < "$file"
}

bootstrap_env() {
    local db="$1"
    shift
    log "初始化 ${db}"
    local f
    for f in "$@"; do
        run_sql "$db" "$INIT_DIR/$f"
    done
}

bootstrap_env "peakflow_test" 01-schema.sql 02-base-data.sql 03-product-data.sql
bootstrap_env "peakflow_prod" 01-schema.sql 02-base-data.sql 03-product-data.sql

log "三套环境初始化完成，各库表数与关键行数："
mysql --user="$DB_USER" --password="$DB_PASS" --table <<'SQL'
SELECT
    t.TABLE_SCHEMA AS `库`,
    COUNT(*)       AS `表数`
FROM information_schema.TABLES t
WHERE t.TABLE_SCHEMA IN ('peakflow_dev', 'peakflow_test', 'peakflow_prod')
GROUP BY t.TABLE_SCHEMA
ORDER BY t.TABLE_SCHEMA;

SELECT
    'peakflow_dev'  AS `库`, (SELECT COUNT(*) FROM peakflow_dev.product)  AS `商品`,
                             (SELECT COUNT(*) FROM peakflow_dev.user)     AS `账号`
UNION ALL SELECT
    'peakflow_test',         (SELECT COUNT(*) FROM peakflow_test.product),
                             (SELECT COUNT(*) FROM peakflow_test.user)
UNION ALL SELECT
    'peakflow_prod',         (SELECT COUNT(*) FROM peakflow_prod.product),
                             (SELECT COUNT(*) FROM peakflow_prod.user);
SQL
