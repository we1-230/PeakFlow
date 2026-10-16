#!/usr/bin/env bash
# ============================================================================
# PeakFlow · 数据库备份（mysqldump + gzip + 保留 N 天）
# ----------------------------------------------------------------------------
# 用法：
#   bash deploy/scripts/backup.sh                 # 备份 + 清理过期
#   bash deploy/scripts/backup.sh --keep 14       # 保留 14 天
#   bash deploy/scripts/backup.sh --dir /data/bak # 换输出目录
#   bash deploy/scripts/backup.sh --quiet         # 静默（给 crontab 用）
#
# 建议加进 crontab（每天 03:10）：
#   (crontab -l 2>/dev/null; echo '10 3 * * * cd /opt/PeakFlow && bash deploy/scripts/backup.sh --quiet >> /var/log/peakflow-backup.log 2>&1') | crontab -
#
# ----------------------------------------------------------------------------
# 为什么用 mysqldump 而不是直接拷 /var/lib/mysql 数据文件：
#   InnoDB 的数据文件是「热」的 —— 直接 cp 出来的一堆 .ibd 文件彼此不一致，
#   恢复时大概率报 "Tablespace is missing for table" 或者表在但数据丢。
#   mysqldump 走的是事务一致性快照（--single-transaction），
#   对线上业务几乎零影响，且产出的 .sql 不依赖 MySQL 版本与页大小。
#
# 为什么默认只备份业务库、显式列出而不是 --all-databases：
#   --all-databases 会把 mysql / sys / performance_schema 这些系统库一起导出，
#   体积大、恢复时还会把线上账号信息覆盖回去（在别人机器上恢复时尤其危险）。
#
# 为什么不用备份 Redis：
#   Redis 在本项目里是「缓存 + 库存预扣 + 幂等标记」，全部可重建：
#   库存与门闩的权威数据在 MySQL（seckill_stock / 订单唯一索引），
#   丢了 Redis 只是「缓存没了」，预热接口一跑就回来了。
#   唯一需要额外注意的是「已预扣但 DB 还没落库」的瞬时窗口 —— 关单兜底任务
#   （OrderSweepTask）与重复下单校验（DB 侧）会把结果收敛到正确状态。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

KEEP_DAYS=7
BACKUP_DIR="$ROOT/backups"
QUIET=0

while [ $# -gt 0 ]; do
    case "$1" in
        --keep)  KEEP_DAYS="${2:-7}"; shift ;;
        --dir)   BACKUP_DIR="${2:-}"; shift ;;
        --quiet) QUIET=1 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "未知参数：$1" >&2; exit 1 ;;
    esac
    shift
done

# 颜色用 $'...'（ANSI-C 引用）而不是 '\033...'：
# 后者是「字面量反斜杠 + 033」，只有 echo -e 才会解释它；
# 写进 heredoc 时会原样打印出 \033[1;36m 这种垃圾。
# 用 $'...' 拿到真正的 ESC 字节，echo / echo -e / printf 都能正确上色。
C_CYAN=$'\033[1;36m'; C_GREEN=$'\033[1;32m'; C_RED=$'\033[1;31m'; C_OFF=$'\033[0m'
say()  { [ "$QUIET" = "1" ] || echo -e "${C_CYAN}▶${C_OFF} $*"; }
ok()   { echo -e "  ${C_GREEN}✓${C_OFF} $*"; }
die()  { echo -e "${C_RED}✗ $*${C_OFF}" >&2; exit 1; }

env_get() {
    local key="$1"
    [ -f .env ] || return 1
    grep -E "^[[:space:]]*${key}=" .env 2>/dev/null | tail -1 | sed -E "s|^[[:space:]]*${key}=||" \
        | sed -E 's/^"(.*)"$/\1/' | sed -E 's/[[:space:]]+$//'
}

MYSQL_CONTAINER=peakflow-mysql
docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -q true \
    || die "$MYSQL_CONTAINER 没在运行。先 bash deploy/scripts/deploy.sh"

DB_PASSWORD="$(env_get DB_PASSWORD || true)"
[ -n "$DB_PASSWORD" ] || die "从 .env 读不到 DB_PASSWORD"

# 备份哪些库：以 .env 的 DB_NAME 为主（生产=peakflow_prod）；
# 如果 dev/test 库也存在，一并带上 —— 它们在同一个实例里，多导一份成本极低，
# 而"误把开发库当生产库操作了"这种事故，有备份能少很多麻烦。
PRIMARY_DB="$(env_get DB_NAME || echo peakflow_prod)"
DBS=("$PRIMARY_DB")
for extra in peakflow_dev peakflow_test; do
    [ "$extra" = "$PRIMARY_DB" ] && continue
    if docker exec -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -uroot -N -B -e "SHOW DATABASES LIKE '$extra'" 2>/dev/null | grep -q "$extra"; then
        DBS+=("$extra")
    fi
done

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
say "备份库：${DBS[*]}  →  $BACKUP_DIR"

TOTAL=0
FAILED=0
for db in "${DBS[@]}"; do
    OUT="$BACKUP_DIR/${db}-${STAMP}.sql.gz"
    # MYSQL_PWD 而不是 -pXXX：命令行参数在 ps 里对同机所有用户可见，等于把密码广播出去
    if docker exec -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" \
        mysqldump -uroot \
            --single-transaction \
            --routines --triggers --events \
            --set-gtid-purged=OFF \
            --default-character-set=utf8mb4 \
            --databases "$db" 2>/dev/null | gzip -6 > "$OUT"; then
        SIZE=$(wc -c < "$OUT" | tr -d ' ')
        if [ "$SIZE" -lt 1024 ]; then
            # 几百字节基本就是"只有表结构"或者干脆是错误信息，不能当成功
            echo -e "  ${C_RED}✗${C_OFF} $db 备份文件异常小（${SIZE}B），已删除"
            rm -f "$OUT"; FAILED=1; continue
        fi
        ok "$db → $(basename "$OUT")  ($(numfmt --to=iec "$SIZE" 2>/dev/null || echo "${SIZE}B"))"
        TOTAL=$((TOTAL + SIZE))
    else
        echo -e "  ${C_RED}✗${C_OFF} $db 导出失败"
        rm -f "$OUT"; FAILED=1
    fi
done

[ "$FAILED" = "0" ] || die "有库备份失败，请查看上面的输出"

# ---------------------------------------------------------------------------
# 清理过期
# ---------------------------------------------------------------------------
say "清理 $KEEP_DAYS 天前的旧备份"
REMOVED=0
while IFS= read -r old; do
    [ -z "$old" ] && continue
    rm -f "$old"; REMOVED=$((REMOVED + 1))
done < <(find "$BACKUP_DIR" -maxdepth 1 -name '*.sql.gz' -type f -mtime "+${KEEP_DAYS}" 2>/dev/null)
[ "$REMOVED" -gt 0 ] && ok "删除 $REMOVED 个过期文件" || ok "没有过期文件"

LEFT="$(find "$BACKUP_DIR" -maxdepth 1 -name '*.sql.gz' -type f 2>/dev/null | wc -l | tr -d ' ')"
DIR_SIZE="$(du -sh "$BACKUP_DIR" 2>/dev/null | awk '{print $1}')"

say "完成"
ok "本次合计 $(numfmt --to=iec "$TOTAL" 2>/dev/null || echo "${TOTAL}B")，目录内共 ${LEFT} 份备份，占用 ${DIR_SIZE}"

# ---------------------------------------------------------------------------
# 恢复方法（打印出来，免得真出事时还要翻文档）
# ---------------------------------------------------------------------------
if [ "$QUIET" = "0" ]; then
cat <<EOF

${C_CYAN}恢复方法${C_OFF}（把 <备份文件> 换成本目录里的某个 .sql.gz）
  1) 解压并导入：
       gunzip -c backups/<备份文件> | \\
         docker exec -i -e MYSQL_PWD="\$(grep '^DB_PASSWORD=' .env | cut -d= -f2-)" \\
           peakflow-mysql mysql -uroot
     （DB_PASSWORD 用 \$(...) 现场取，不要把明文密码写进 shell 历史）

  2) 备份里带了 CREATE DATABASE IF NOT EXISTS，所以会直接覆盖同名库。
     想恢复到另一个名字（先比对再切换）用：
       gunzip -c backups/<备份文件> > /tmp/restore.sql
       sed -i 's/\`peakflow_prod\`/\`peakflow_prod_restore\`/g' /tmp/restore.sql
       docker exec -i ... mysql -uroot < /tmp/restore.sql

${C_CYAN}异地备份${C_OFF}
  本脚本只备份到本机磁盘。同一台机器上，磁盘坏了备份也一起没。
  演示站的最低成本做法：每周手工把 backups/ 里的最新一份下载到本地。例如在
  你自己的电脑上执行：
       scp peakflow@<服务器IP>:/opt/PeakFlow/backups/<最新文件> ./

EOF
fi
