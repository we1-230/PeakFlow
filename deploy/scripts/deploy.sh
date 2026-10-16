#!/usr/bin/env bash
# ============================================================================
# PeakFlow · 一键部署脚本
# ----------------------------------------------------------------------------
# 作用：把「服务器上部署/更新一套 PeakFlow」浓缩成一条命令，并自动处理那几个
#       手工做很容易漏、漏了就会出事故的点。
#
# 用法（服务器上，项目根目录）：
#   bash deploy/scripts/deploy.sh              # 默认：重建镜像 + 按 .env 决定形态
#   bash deploy/scripts/deploy.sh --no-build   # 只重启容器，不重新构建镜像
#   bash deploy/scripts/deploy.sh --mq on      # 强制开启 RocketMQ（并写回 .env）
#                                              # ⚠️ --mq off 已不支持，见下方坑①
#   bash deploy/scripts/deploy.sh --dry-run    # 只打印将要执行的命令，不动手
#   bash deploy/scripts/deploy.sh --dry-run    # 只打印将要执行的命令，不动手
#
# ----------------------------------------------------------------------------
# 本脚本替你兜住的 5 个坑（每一个都真实踩过或验证过）：
#
#  ① 【MQ 开关：当前版本只支持开启】
#      本仓库的 Java 侧 4 个消费者【始终注册】，没有 @ConditionalOnProperty 条件开关。
#      因此关掉 broker/namesrv 会让秒杀下单链路静默失效
#      （页面打得开、一下单就失败，表象极有迷惑性）。
#      所以 .env 里 PEAKFLOW_MQ_ENABLED 必须为 true；读到 false 时本脚本会
#      直接报错并说明需要补齐哪些改动，而不是"静默降级"或引用一个不存在的 compose 文件。
#      本项目实测常驻内存约 2.2GB ⇒ 请用 4G 及以上规格的机器部署。
#
#  ② 【后端重建后必须重启 nginx】
#      nginx 在【启动那一刻】把 upstream 里的主机名（backend1）解析成固定 IP，
#      之后不再查 Docker 内嵌 DNS。而 --force-recreate / 重新构建后端会让容器
#      换一个 IP ⇒ nginx 仍指向已废弃的旧 IP ⇒
#      连接超时 10s（正是 fail_timeout 的值）后返回 504。
#      ★ 这个问题的表现极具迷惑性：后端日志里【完全没有这条请求】，
#        看起来像"后端挂了"，其实是 nginx 根本没连上。
#      本脚本在 up 之后固定重启 nginx，代价约 0.5s 不可用。
#
#  ③ 【日志目录属主】
#      后端镜像以 UID/GID 1001（peakflow）运行，而 ./backend/logs/backend1 是
#      bind mount。宿主机目录若属主不对，容器内写日志直接 Permission denied，
#      表现是"启动成功但没有任何日志文件"。本脚本会 mkdir + chown 1001:1001。
#
#  ④ 【depends_on 的启动顺序陷阱】
#      `docker compose up -d backend1` 会把 backend1 的依赖（含 broker）一起拉起。
#      想单独验证某个服务必须加 --no-deps。本脚本一次性 up 全栈，并等每个
#      服务的 healthcheck 变 healthy 才继续，避免"后端起来了但 broker 还没好"。
#
#  ⑤ 【HTTPS 层要在证书存在之后才叠加】
#      第一次签发证书时容器里还没有证书，加不了 443 层；签完之后必须叠加。
#      本脚本检查 /etc/letsencrypt/live/$DOMAIN/fullchain.pem 是否存在来决定，
#      不需要人记"现在该不该加 -f docker-compose.https.yml"。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# ---------------------------------------------------------------------------
# 参数
# ---------------------------------------------------------------------------
DO_BUILD=1
DRY_RUN=0
MQ_OVERRIDE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) DO_BUILD=0 ;;
        --build)    DO_BUILD=1 ;;
        --dry-run)  DRY_RUN=1 ;;
        --mq)       MQ_OVERRIDE="${2:-}"; shift
                    case "$MQ_OVERRIDE" in
                        on|true)  MQ_OVERRIDE=true ;;
                        off|false) MQ_OVERRIDE=false ;;
                        *) echo "✗ --mq 只接受 on/off（或 true/false），收到：$MQ_OVERRIDE" >&2; exit 1 ;;
                    esac ;;
        -h|--help)  sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "✗ 未知参数：$1（用 --help 看用法）" >&2; exit 1 ;;
    esac
    shift
done

# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
C_CYAN='\033[1;36m'; C_GREEN='\033[1;32m'; C_RED='\033[1;31m'
C_YELLOW='\033[1;33m'; C_DIM='\033[2m'; C_OFF='\033[0m'

step() { echo -e "\n${C_CYAN}▶ $*${C_OFF}"; }
ok()   { echo -e "  ${C_GREEN}✓${C_OFF} $*"; }
warn() { echo -e "  ${C_YELLOW}⚠${C_OFF} $*"; }
die()  { echo -e "\n${C_RED}✗ $*${C_OFF}" >&2; exit 1; }
dim()  { echo -e "  ${C_DIM}$*${C_OFF}"; }

run() {
    if [ "$DRY_RUN" = "1" ]; then
        echo -e "  ${C_DIM}[dry-run] $*${C_OFF}"
    else
        "$@"
    fi
}

# ---------------------------------------------------------------------------
# 读 .env（不 source：.env 里可能有空格/井号/引号，source 容易炸，
# 而且一旦有人在 .env 里插了命令就是直接执行 —— 读文本更安全）
# ---------------------------------------------------------------------------
env_get() {
    local key="$1"
    [ -f .env ] || return 1
    # 取最后一个匹配（后写的覆盖先写的，与 Compose 的行为一致）
    grep -E "^[[:space:]]*${key}=" .env 2>/dev/null | tail -1 | sed -E "s|^[[:space:]]*${key}=||" \
        | sed -E 's/^"(.*)"$/\1/' | sed -E 's/[[:space:]]+$//'
}

env_set() {
    local key="$1" val="$2"
    if grep -qE "^[[:space:]]*${key}=" .env; then
        run sed -i -E "s|^[[:space:]]*${key}=.*|${key}=${val}|" .env
    else
        run bash -c "printf '\n%s=%s\n' '$key' '$val' >> .env"
    fi
}

# ---------------------------------------------------------------------------
# 环境自检
# ---------------------------------------------------------------------------
step "环境自检"

[ -f .env ] || die "找不到 .env。请先执行：
    cp .env.example .env && bash deploy/scripts/gen-prod-env.sh <对外访问地址> [端口] [域名] [MQ开关]"
ok "找到 .env"

command -v docker >/dev/null 2>&1 || die "未安装 docker。先执行：sudo bash deploy/scripts/server-init.sh"
docker info >/dev/null 2>&1 || die "无法连接 Docker 守护进程。当前用户可能不在 docker 组：
    sudo usermod -aG docker \$USER && newgrp docker   # 然后重新登录
    或者用 sudo 执行本脚本"
ok "Docker 可用：$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?')"

docker compose version >/dev/null 2>&1 || die "缺少 docker compose 插件（v2）。"
ok "Compose 可用：$(docker compose version --short 2>/dev/null || echo '?')"

# ---------------------------------------------------------------------------
# 关键配置
# ---------------------------------------------------------------------------
SPRING_PROFILE="$(env_get SPRING_PROFILES_ACTIVE || echo dev)"
[ "$SPRING_PROFILE" = "prod" ] || warn "SPRING_PROFILES_ACTIVE=$SPRING_PROFILE（生产应为 prod，可能是还没跑 gen-prod-env.sh）"

NGINX_PORT="$(env_get NGINX_PORT || echo 80)"
NGINX_HTTPS_PORT="$(env_get NGINX_HTTPS_PORT || echo 443)"
PUBLIC_BASE_URL="$(env_get PUBLIC_BASE_URL || echo '')"
DOMAIN="$(env_get DOMAIN || echo '')"
MQ_ENABLED="$(env_get PEAKFLOW_MQ_ENABLED || echo true)"

if [ -n "$MQ_OVERRIDE" ] && [ "$MQ_OVERRIDE" != "$MQ_ENABLED" ]; then
    step "按参数把 PEAKFLOW_MQ_ENABLED 从 $MQ_ENABLED 改为 $MQ_OVERRIDE"
    env_set PEAKFLOW_MQ_ENABLED "$MQ_OVERRIDE"
    MQ_ENABLED="$MQ_OVERRIDE"
fi

# ---------------------------------------------------------------------------
# 组装 compose 文件栈
# ---------------------------------------------------------------------------
COMPOSE_FILES=(-f docker-compose.yml -f docker-compose.prod.yml)

  # ⚠️ 这里【故意】不再叠加 docker-compose.no-mq.yml，也不再支持关闭 MQ。
  #
  #   为什么：本仓库的 Java 侧【始终注册 4 个 RocketMQ 消费者】，
  #   consumer/ 下没有任何 @ConditionalOnProperty，也没有读取 PEAKFLOW_MQ_ENABLED 的开关类。
  #   于是把 namesrv/broker 关掉之后：
  #     · 消费者照常注册 → 连不上 namesrv，秒杀下单链路直接坏掉；
  #     · 表面现象是"容器都起来了、页面也能开"，但一下单就失败 —— 很难查。
  #   （曾有一版用 MqSwitch + @ConditionalOnProperty 实现过真开关，
  #     后来因为确定走 4G 完整栈而整体回退了，一并删掉了 no-mq 覆盖文件。）
  #
  #   所以这里选择【明确报错】而不是静默降级或引用一个不存在的文件：
  #   要支持 2G 机器部署，得先把 Java 侧开关加回来，再补 docker-compose.no-mq.yml。
  if [ "$MQ_ENABLED" = "false" ]; then
      die "当前版本不支持关闭 RocketMQ（PEAKFLOW_MQ_ENABLED=false）。
   原因：Java 侧 4 个消费者没有条件开关，关掉 broker 会让秒杀下单链路静默失效。

   要做 2G 机器的部署，需要先补齐三件事：
     ① 为 consumer/ 下的消费者加 @ConditionalOnProperty(prefix=\"peakflow.mq\", name=\"enabled\")
     ② 提供一个 MqSwitch 之类的配置类读取该开关
     ③ 补回 docker-compose.no-mq.yml（把 namesrv/broker 划入 profile，
        并用 !override 摘掉 backend1 对 broker 的 depends_on）
   在此之前请：把 .env 里的 PEAKFLOW_MQ_ENABLED 设为 true，
   或改用 4G 及以上规格的机器（本项目实测常驻约 2.2GB，2G 会 OOM）。"
  fi

CERT_DIR="/etc/letsencrypt/live/${DOMAIN:-__none__}"
USE_HTTPS=0
# ⚠️ 这里必须兼容「非 root 的部署账号」判存在性：
#   /etc/letsencrypt/live 与 /archive 默认是 `drwx------ root root`（700），
#   certbot 有意这样设计（防止同机其他用户读到 privkey）。
#   于是部署账号执行裸 `[ -f ... ]` 会因权限不足返回假 ⇒ 误判"证书不存在"
#   ⇒ 明明签好了却不叠加 HTTPS 层，表现为「443 一直不通」。
#   注意：这里只需要【判断存在】，不需要读内容 ——
#   证书是通过 bind mount 由 Docker 守护进程（root）挂进 nginx 容器的，
#   部署账号没有读权限也完全不影响运行。
#   也【不要】图省事把 live/archive chmod 755 —— 那等于把私钥暴露给所有本地用户。
cert_present() {
    [ -f "$1" ] 2>/dev/null && return 0
    command -v sudo >/dev/null 2>&1 && sudo -n test -f "$1" 2>/dev/null && return 0
    return 1
}
if [ -n "$DOMAIN" ] && cert_present "${CERT_DIR}/fullchain.pem"; then
    COMPOSE_FILES+=(-f docker-compose.https.yml)
    USE_HTTPS=1
fi

step "部署形态"
ok "profile        : $SPRING_PROFILE"
ok "RocketMQ       : $([ "$MQ_ENABLED" = "true" ] && echo '开启（完整形态）' || echo '关闭（省约 920MB 内存）')"
ok "对外端口       : ${NGINX_PORT}（HTTP）$([ "$USE_HTTPS" = "1" ] && echo " / ${NGINX_HTTPS_PORT}（HTTPS）")"
ok "DOMAIN         : ${DOMAIN:-（未设置）}"
if [ -n "$DOMAIN" ] && [ "$USE_HTTPS" = "0" ]; then
    warn "DOMAIN 已设置但没找到证书 ${CERT_DIR}/fullchain.pem ⇒ 本次不叠加 HTTPS 层"
    dim "签证书：bash deploy/scripts/issue-cert.sh $DOMAIN"
fi
ok "对外地址       : ${PUBLIC_BASE_URL:-（未设置）}"
dim "compose 文件栈：docker compose ${COMPOSE_FILES[*]}"

# 打印最终生效的服务清单（能提前暴露 profile / !override 类合并问题）
if [ "$DRY_RUN" = "0" ]; then
    SERVICES="$(docker compose "${COMPOSE_FILES[@]}" config --services 2>/dev/null | tr '\n' ' ')"
    ok "将启动服务     : $SERVICES"
fi

# ---------------------------------------------------------------------------
# 坑③：日志目录属主
# ---------------------------------------------------------------------------
step "准备宿主机目录（日志属主 UID/GID 1001）"
run mkdir -p backend/logs/backend1
# 容器内是 peakflow(1001)。用 sudo 时 chown 才成功；不用 sudo 时若目录已属当前用户
# 且用户恰好是 1001 也没问题，失败不致命（只是会没有日志文件），所以不 die。
if [ "$(id -u)" = "0" ]; then
    run chown -R 1001:1001 backend/logs
elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    run sudo chown -R 1001:1001 backend/logs
else
    warn "无 root 且无免密 sudo，跳过 chown backend/logs"
    dim "若启动后 backend 没有任何日志文件，手工执行：sudo chown -R 1001:1001 backend/logs"
fi
# ACME webroot（certbot 与 nginx 共用；nginx 只读挂载它）
if [ "$(id -u)" = "0" ]; then
    run mkdir -p /var/www/certbot
elif sudo -n true 2>/dev/null; then
    run sudo mkdir -p /var/www/certbot
else
    dim "/var/www/certbot 不存在时 Docker 会自动建空目录，无副作用"
fi

# ---------------------------------------------------------------------------
# 构建 + 启动
# ---------------------------------------------------------------------------
step "构建镜像并启动"
UP_ARGS=(-d --remove-orphans)
[ "$DO_BUILD" = "1" ] && UP_ARGS+=(--build)

dim "docker compose ${COMPOSE_FILES[*]} up ${UP_ARGS[*]}"
if [ "$DRY_RUN" = "0" ]; then
    docker compose "${COMPOSE_FILES[@]}" up "${UP_ARGS[@]}"
fi

# ---------------------------------------------------------------------------
# 等健康
# ---------------------------------------------------------------------------
wait_healthy() {
    local name="$1" limit="${2:-180}" waited=0 status
    while [ "$waited" -lt "$limit" ]; do
        status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' "$name" 2>/dev/null || echo missing)"
        case "$status" in
            healthy) ok "$name 已就绪"; return 0 ;;
            nohealth) ok "$name 已运行（无 healthcheck）"; return 0 ;;
            missing) : ;;
        esac
        sleep 3; waited=$((waited + 3))
    done
    warn "$name 等待 ${limit}s 仍未 healthy（当前：${status}）"
    dim "查看日志：docker logs --tail=80 $name"
    return 1
}

if [ "$DRY_RUN" = "0" ]; then
    step "等待服务就绪"
    FAILED=0
    wait_healthy peakflow-mysql 120 || FAILED=1
    wait_healthy peakflow-redis 60  || FAILED=1
    if [ "$MQ_ENABLED" = "true" ]; then
        wait_healthy peakflow-rmq-namesrv 120 || FAILED=1
        wait_healthy peakflow-rmq-broker 180  || FAILED=1
    fi
    # 后端冷启动约 6~10s，healthcheck 有 60s start-period
    wait_healthy peakflow-backend1 240 || FAILED=1
    wait_healthy peakflow-nginx 60 || FAILED=1
    [ "$FAILED" = "0" ] || die "有服务未就绪，部署中断。上面的提示里有排查入口。"
fi

# ---------------------------------------------------------------------------
# 坑②：重启 nginx，强制重新解析 upstream
# ---------------------------------------------------------------------------
step "重启 nginx 以重新解析后端地址"
dim "原因：nginx 只在启动时把 upstream 主机名解析成固定 IP。后端容器若被重建就会换 IP，"
dim "      nginx 仍指向旧 IP ⇒ 连接超时 10s ⇒ 504，且后端日志里看不到任何请求。"
if [ "$DRY_RUN" = "0" ]; then
    docker compose "${COMPOSE_FILES[@]}" restart nginx >/dev/null
    sleep 3
    ok "nginx 已重启"
else
    dim "[dry-run] docker compose ${COMPOSE_FILES[*]} restart nginx"
fi

# ---------------------------------------------------------------------------
# 冒烟测试
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" = "0" ]; then
    step "冒烟测试（本机 curl 走 nginx）"
    BASE="http://127.0.0.1:${NGINX_PORT}"
    SMOKE_FAIL=0

    probe() {   # probe <描述> <期望码> <curl 参数...>
        local desc="$1" want="$2"; shift 2
        local code
        code="$(curl -s -o /dev/null -m 15 -w '%{http_code}' "$@" 2>/dev/null || echo 000)"
        if [ "$code" = "$want" ]; then
            ok "$desc → $code"
        else
            echo -e "  ${C_RED}✗${C_OFF} $desc → $code（期望 $want）"
            SMOKE_FAIL=1
        fi
    }

    probe "GET  /healthz"            200 "$BASE/healthz"
    probe "GET  /（前端首页）"       200 "$BASE/"
    probe "POST /api/seckill/list"   200 -X POST -H 'Content-Type: application/json' -d '{"pageNum":1,"pageSize":5}' "$BASE/api/seckill/list"
    probe "POST /api/product/page"   200 -X POST -H 'Content-Type: application/json' -d '{"pageNum":1,"pageSize":5}' "$BASE/api/product/page"

    # 安全响应头（生产 nginx 配置里 include 出来的那四个）
    HDRS="$(curl -s -D - -o /dev/null -m 15 "$BASE/" 2>/dev/null || true)"
    # 含 HSTS：它和其余 4 个头放在同一个 snippet 里（见
    # deploy/nginx/snippets/peakflow-security-headers.inc 的说明），
    # HTTP / HTTPS 两种形态下都会下发，所以这里恒检查。
    # 加进冒烟的意义：HSTS 极易因为「location 里设了别的 add_header」而被静默覆盖掉，
    # 配置里能 grep 到、线上却下发不了 —— 只有真的 curl 一次才能发现。
    for h in X-Content-Type-Options X-Frame-Options Referrer-Policy Permissions-Policy Strict-Transport-Security; do
        if printf '%s' "$HDRS" | grep -qi "^${h}:"; then
            ok "安全头 $h 存在"
        else
            echo -e "  ${C_RED}✗${C_OFF} 安全头 $h 缺失"
            SMOKE_FAIL=1
        fi
    done

    # 后端健康详情（直连容器，确认不是靠 nginx 缓存骗过去）
    if docker exec peakflow-backend1 sh -c 'curl -fsS http://localhost:8080/actuator/health' >/dev/null 2>&1; then
        ok "backend1 容器内自检 /actuator/health 通过"
    else
        echo -e "  ${C_RED}✗${C_OFF} backend1 容器内自检失败"
        SMOKE_FAIL=1
    fi

    [ "$SMOKE_FAIL" = "0" ] || die "冒烟测试未全部通过，先别对外宣布部署成功。"
fi

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
step "部署完成"
echo ""
echo -e "  ${C_GREEN}访问地址${C_OFF}"
if [ "$USE_HTTPS" = "1" ]; then
    echo "    https://${DOMAIN}"
else
    echo "    ${PUBLIC_BASE_URL:-http://<服务器公网IP>:${NGINX_PORT}}"
fi
echo ""
echo -e "  ${C_DIM}常用命令${C_OFF}"
echo "    查看状态   docker compose ${COMPOSE_FILES[*]} ps"
echo "    看后端日志 docker logs -f --tail=200 peakflow-backend1"
echo "    看 nginx    docker logs -f --tail=100 peakflow-nginx"
echo "    停止       docker compose ${COMPOSE_FILES[*]} down"
echo "    内存占用   docker stats --no-stream"
echo ""
if [ "$MQ_ENABLED" = "false" ]; then
    echo -e "  ${C_YELLOW}RocketMQ 已关闭${C_OFF}，本机常驻内存约 1.5GB。"
    echo "    秒杀下单改为同步落库；订单超时由 OrderSweepTask 每 60s 扫表兜底，"
    echo "    因此关单时间会是 10~11 分钟而不是精确 10 分钟（预期行为，不是 bug）。"
    echo ""
fi
echo -e "  ${C_DIM}数据库备份：bash deploy/scripts/backup.sh（建议加进 crontab）${C_OFF}"
echo ""
