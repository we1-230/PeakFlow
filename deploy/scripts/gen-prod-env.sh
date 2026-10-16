#!/usr/bin/env bash
# ============================================================================
# PeakFlow · 生成生产环境变量文件（.env）
# ----------------------------------------------------------------------------
# 作用：把 .env.example（模板，可进版本库）复制成 .env（真实值，不进版本库），
#       并把里面【必须换掉的】与【生产形态必须不同的】那些项改成生产值。
#
# 为什么不能直接把本机的 .env 拷到服务器：
#   1) 本机 .env 里的 JWT_SECRET / MOCK_PAY_SECRET / DB_PASSWORD 是开发用的，
#      一旦随代码或备份外流，等于线上密钥泄露；
#   2) 本机的 DB_NAME=peakflow_dev、REDIS_DATABASE=0 指向开发库，
#      生产 profile 虽然默认会去找 peakflow_prod / db2，
#      但环境变量优先级更高 —— 不显式覆盖就会连到开发库上，属于"数据串台"事故。
#
# 用法：
#   sudo bash deploy/scripts/gen-prod-env.sh <对外访问地址> [NGINX端口] [域名] [MQ开关]
#
#   例（备案未通过，先用 IP+8080 验收）：
#     sudo bash deploy/scripts/gen-prod-env.sh http://1.2.3.4:8080 8080
#   例（备案通过，域名 + 80/443，2核4G 及以上）：
#     sudo bash deploy/scripts/gen-prod-env.sh https://peakflow.top 80 peakflow.top true
#   例（2核2G 小规格，必须关 MQ 省内存）：
#     sudo bash deploy/scripts/gen-prod-env.sh https://peakflow.top 80 peakflow.top false
#
# 参数说明：
#   对外访问地址：浏览器里真正输入的地址（用于拼支付收银台地址、本地图片直链）
#   NGINX端口   ：默认 8080（未备案时国内服务器 80/443 会被拦）
#   域名        ：可选；填了才会写进 .env，HTTPS 那一层要用
#   MQ开关      ：当前版本【只支持 true】（默认）。传 false 会直接报错退出，
#                 原因见本脚本参数校验处的说明（Java 侧消费者没有条件开关）。
# ============================================================================
set -euo pipefail

PUBLIC_BASE_URL="${1:-}"
NGINX_PORT="${2:-8080}"
DOMAIN="${3:-}"
MQ_ENABLED="${4:-true}"

case "$MQ_ENABLED" in
    true) ;;
    false)
        echo "第 4 个参数 MQ开关 当前版本【只支持 true】。" >&2
        echo "  原因：Java 侧 4 个消费者没有 @ConditionalOnProperty 条件开关，" >&2
        echo "  关掉 broker/namesrv 后消费者仍会注册，秒杀下单链路会【静默失效】" >&2
        echo "  （页面打得开、一下单就失败，很难排查）。" >&2
        echo "  要做 2G 机器部署，需先补：① consumer/ 加条件开关 ② 补回 docker-compose.no-mq.yml。" >&2
        echo "  本项目实测常驻内存约 2.2GB，请直接用 4G 及以上规格的机器。" >&2
        exit 1 ;;
    *) echo "第 4 个参数 MQ开关 只能是 true，收到：$MQ_ENABLED" >&2; exit 1 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

log()  { echo -e "\n\033[1;36m[gen-prod-env]\033[0m $*"; }
die()  { echo -e "\033[1;31m[gen-prod-env][失败]\033[0m $*" >&2; exit 1; }

[ -n "$PUBLIC_BASE_URL" ] || die "缺少参数。用法：bash $0 <对外访问地址> [NGINX端口] [域名]"
[ -f "$ROOT/.env.example" ] || die "找不到 $ROOT/.env.example"

ENV_FILE="$ROOT/.env"
if [ -e "$ENV_FILE" ]; then
    BAK="${ENV_FILE}.bak.$(date +%Y%m%d%H%M%S)"
    cp "$ENV_FILE" "$BAK"
    log "已存在 .env，已备份为 $(basename "$BAK")，将重新生成"
fi

command -v openssl >/dev/null 2>&1 || die "缺少 openssl，无法生成随机密钥（apt-get install -y openssl）"

# ---------------------------------------------------------------------------
# 密钥生成：只用字母数字，避免出现 / + = 等特殊字符。
# 为什么要在意这个：这些值会被写进 .env、再由 Docker 注入容器，
# 特殊字符在某些 shell 展开场景（heredoc、未加引号的变量）里会出问题，
# 排查起来极其费劲。用纯字母数字，长度足够就不损失强度。
# ---------------------------------------------------------------------------
rand_alnum() {
    # $1 = 需要的字符数
    #
    # ⚠️ 这里必须把 SIGPIPE 的退出码吞掉，否则整个脚本会在第一行就静默死掉：
    #   `head -c N` 读够 N 字节后立刻退出并关闭管道 ⇒ 上游 `tr` 收到 SIGPIPE
    #   而以 141（128+13）退出 ⇒ 在 `set -o pipefail` 下整条管道被判为失败
    #   ⇒ `set -e` 立即中断脚本。表现是「脚本一个字都不输出、.env 也没生成」，
    #   极难排查（因为没有任何报错）。
    #   注意：吞掉 SIGPIPE 后必须【显式校验长度】，否则万一 /dev/urandom 取不到
    #   数据，空密码会被静默写进 .env —— 那是最危险的失败方式。
    local n="$1" out
    out="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom 2>/dev/null | head -c "$n" || true)"
    if [ "${#out}" -ne "$n" ]; then
        echo "[gen-prod-env][失败] 随机数生成异常：期望 ${n} 位，实际 ${#out} 位" >&2
        exit 1
    fi
    printf '%s' "$out"
}

DB_PASSWORD="$(rand_alnum 32)"
JWT_SECRET="$(rand_alnum 64)"
MOCK_PAY_SECRET="$(rand_alnum 40)"

# 覆盖/追加一个键值。值里可能出现 / 与 : ，所以分隔符用 | 而不是 /
set_kv() {
    local key="$1" val="$2" file="$3"
    if grep -qE "^[[:space:]]*${key}=" "$file"; then
        sed -i -E "s|^[[:space:]]*${key}=.*|${key}=${val}|" "$file"
    else
        printf '\n%s=%s\n' "$key" "$val" >> "$file"
    fi
}

log "从模板生成 $ENV_FILE"
cp "$ROOT/.env.example" "$ENV_FILE"

# ---------------------------------------------------------------------------
# ① 运行环境与端口
# ---------------------------------------------------------------------------
set_kv SPRING_PROFILES_ACTIVE prod "$ENV_FILE"
set_kv NGINX_PORT            "$NGINX_PORT" "$ENV_FILE"
set_kv NGINX_HTTPS_PORT      443 "$ENV_FILE"
set_kv PUBLIC_BASE_URL       "$PUBLIC_BASE_URL" "$ENV_FILE"
[ -n "$DOMAIN" ] && set_kv DOMAIN "$DOMAIN" "$ENV_FILE"

# ---------------------------------------------------------------------------
# ② 数据库：必须指向生产库，并且换一套强密码
#    注意 DB_HOST / DB_PORT 不用改：容器里由 docker-compose.yml 的
#    x-backend-env 锚点统一覆盖成 mysql:3306（走容器网络，不走宿主机端口）。
# ---------------------------------------------------------------------------
set_kv DB_NAME     peakflow_prod "$ENV_FILE"
set_kv DB_USERNAME root          "$ENV_FILE"
set_kv DB_PASSWORD "$DB_PASSWORD" "$ENV_FILE"
# 容器之间走的是 Docker 私有桥接网络，流量不出宿主机，没必要再叠一层 TLS 握手
# （prod profile 里默认是 true，这里显式关掉并说明原因，避免"照着默认走"）
set_kv DB_USE_SSL  false "$ENV_FILE"

# ---------------------------------------------------------------------------
# ③ Redis：必须切到独立的逻辑库（prod = db2）
#    prod profile 里写的是 ${REDIS_DATABASE:2}，但环境变量存在时优先用环境变量。
#    模板里是 0（dev），不改就会让生产后端读到开发环境的 Key（库存、门闩全串）。
# ---------------------------------------------------------------------------
set_kv REDIS_DATABASE 2 "$ENV_FILE"

# ---------------------------------------------------------------------------
# ④ 业务密钥：全部换新
# ---------------------------------------------------------------------------
set_kv JWT_SECRET      "$JWT_SECRET"      "$ENV_FILE"
set_kv MOCK_PAY_SECRET "$MOCK_PAY_SECRET" "$ENV_FILE"

# ---------------------------------------------------------------------------
# ⑤ 前后端回调地址
#    MOCK_PAY_RETURN_URL 不在 docker-compose 的锚点覆盖范围内，
#    会直接取 .env 的值，所以这里必须跟着对外地址一起改，
#    否则用户付款完成后会被跳回 localhost。
# ---------------------------------------------------------------------------
set_kv MOCK_PAY_RETURN_URL "${PUBLIC_BASE_URL}/orders" "$ENV_FILE"

# ---------------------------------------------------------------------------
# ⑥ CORS
#    本项目的浏览器请求都走同源 /api（由 nginx 反代），理论上不触发跨域。
#    但显式配置是对的：万一以后前端被放到别的域名，这里就是唯一开关。
# ---------------------------------------------------------------------------
if [ -n "$DOMAIN" ]; then
    set_kv CORS_ALLOWED_ORIGINS "https://${DOMAIN},https://www.${DOMAIN},http://${DOMAIN}" "$ENV_FILE"
else
    set_kv CORS_ALLOWED_ORIGINS "$PUBLIC_BASE_URL" "$ENV_FILE"
fi

# ---------------------------------------------------------------------------
# ⑦ 日志
#    LOG_PATH 用容器内绝对路径：容器里 WORKDIR=/app，
#    而 compose 把 ./backend/logs/backend1 挂到了 /app/logs。
#    LOG_LEVEL_APP 从模板的 DEBUG 降到 INFO：DEBUG 会打印 SQL 与参数，
#    既吃磁盘又可能把手机号之类的字段写进日志文件。
# ---------------------------------------------------------------------------
set_kv LOG_PATH       /app/logs "$ENV_FILE"
set_kv LOG_LEVEL_ROOT INFO      "$ENV_FILE"
set_kv LOG_LEVEL_APP  INFO      "$ENV_FILE"

  # ---------------------------------------------------------------------------
  # ⑧ RocketMQ 总开关（当前版本只支持 true，参数校验处会拦住 false）
  #    为什么不做成可关：Java 侧 4 个消费者没有条件开关，关掉 broker 会让
  #    秒杀下单链路静默失效。本项目实测常驻内存约 2.2GB ⇒ 请用 4G 及以上机器。
  # ---------------------------------------------------------------------------
  set_kv PEAKFLOW_MQ_ENABLED "$MQ_ENABLED" "$ENV_FILE"

# ---------------------------------------------------------------------------
# ⑨ 对象存储：留空 = 走本地降级上传（文件落在 backend/uploads 数据卷里）
#    演示站用不上 OSS，留空可以少一个外部依赖（也少一份被泄露的 AK）。
# ---------------------------------------------------------------------------
set_kv OSS_ACCESS_KEY_ID     "" "$ENV_FILE"
set_kv OSS_ACCESS_KEY_SECRET "" "$ENV_FILE"

chmod 600 "$ENV_FILE"

log "生成完成，非敏感项预览（密钥已隐藏）"
grep -E '^(SPRING_PROFILES_ACTIVE|NGINX_PORT|NGINX_HTTPS_PORT|PUBLIC_BASE_URL|DOMAIN|PEAKFLOW_MQ_ENABLED|DB_NAME|DB_USERNAME|DB_USE_SSL|REDIS_DATABASE|LOG_PATH|LOG_LEVEL_ROOT|LOG_LEVEL_APP|MOCK_PAY_RETURN_URL|CORS_ALLOWED_ORIGINS)=' "$ENV_FILE" | sed 's/^/  /'
echo "  DB_PASSWORD       = **********（32 位随机）"
echo "  JWT_SECRET        = **********（64 位随机）"
echo "  MOCK_PAY_SECRET   = **********（40 位随机）"

  cat <<'TIP'

  ── 重要提醒 ────────────────────────────────────────────
  · .env 已在 .gitignore 里，永远不要提交、不要发到聊天工具里。
  · 换域名/换端口后要重新跑一次本脚本？不用 —— 直接改 .env 里对应几行即可，
    但不要改 DB_PASSWORD：MySQL 数据卷里的 root 密码只在【首次初始化】时生效，
    改了会导致后端连不上库（要改就得连数据卷一起重建）。
  · 想确认生成结果：sudo cat .env | grep -v SECRET
  ────────────────────────────────────────────────────────
TIP
