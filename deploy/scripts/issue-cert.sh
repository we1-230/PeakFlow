#!/usr/bin/env bash
# ============================================================================
# PeakFlow · Let's Encrypt 证书签发 / 续期
# ----------------------------------------------------------------------------
# 用法：
#   bash deploy/scripts/issue-cert.sh <域名> [邮箱]     # 首次签发
#   bash deploy/scripts/issue-cert.sh --renew           # 续期（建议加进 crontab）
#   bash deploy/scripts/issue-cert.sh --staging <域名>  # 用 staging 环境试跑，不消耗配额
#
# ----------------------------------------------------------------------------
# 前置条件（少一个都会失败，脚本会先自检）：
#   ① 域名已解析到本机公网 IP
#   ② 国内机房：域名必须【已备案】，否则运营商会在 80 端口拦截，
#      Let's Encrypt 的 HTTP-01 校验请求根本到不了这台机器。
#   ③ nginx 已按【HTTP（不含 HTTPS 层）】跑起来：
#        bash deploy/scripts/deploy.sh
#      这一步很关键 —— 签证书时容器里还没有证书，加不了 443 层。
#      docker-compose.prod.yml 已经在 prod 层挂了 /var/www/certbot，
#      所以 HTTP 形态下 ACME 校验路径就是通的。
#
# ----------------------------------------------------------------------------
# 为什么用 webroot 而不是 standalone：
#   standalone 需要临时停掉 nginx 独占 80 端口。webroot 只需要往
#   /var/www/certbot 里写一个文件，nginx 的 `location ^~ /.well-known/acme-challenge/`
#   会把它读出去（见 deploy/nginx/snippets/peakflow-locations.inc），
#   全程不中断服务。
#
# 为什么用 docker 版 certbot 而不是宿主机装的：
#   宿主机 apt 版 certbot 版本随发行版走，老版本对新 ACME 协议支持不全；
#   容器版永远是新的，而且与 nginx 容器共享同一份 /etc/letsencrypt 与
#   /var/www/certbot，不受"装在哪儿"影响。server-init.sh 里装的 apt 版
#   只作为兜底（本脚本优先用容器版）。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

C_CYAN='\033[1;36m'; C_GREEN='\033[1;32m'; C_RED='\033[1;31m'
C_YELLOW='\033[1;33m'; C_DIM='\033[2m'; C_OFF='\033[0m'
step() { echo -e "\n${C_CYAN}▶ $*${C_OFF}"; }
ok()   { echo -e "  ${C_GREEN}✓${C_OFF} $*"; }
warn() { echo -e "  ${C_YELLOW}⚠${C_OFF} $*"; }
die()  { echo -e "\n${C_RED}✗ $*${C_OFF}" >&2; exit 1; }
dim()  { echo -e "  ${C_DIM}$*${C_OFF}"; }

WEBROOT_HOST=/var/www/certbot
LE_HOST=/etc/letsencrypt
CERTBOT_IMAGE=certbot/certbot:latest

env_get() {
    local key="$1"
    [ -f .env ] || return 1
    grep -E "^[[:space:]]*${key}=" .env 2>/dev/null | tail -1 | sed -E "s|^[[:space:]]*${key}=||" \
        | sed -E 's/^"(.*)"$/\1/' | sed -E 's/[[:space:]]+$//'
}

# ---------------------------------------------------------------------------
# 参数
# ---------------------------------------------------------------------------
MODE=issue
STAGING=0
DOMAIN=""
EMAIL=""

while [ $# -gt 0 ]; do
    case "$1" in
        --renew)   MODE=renew ;;
        --staging) STAGING=1 ;;
        -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
        -*) die "未知参数：$1" ;;
        *)
            if [ -z "$DOMAIN" ]; then DOMAIN="$1"
            elif [ -z "$EMAIL" ]; then EMAIL="$1"
            else die "多余的参数：$1"
            fi ;;
    esac
    shift
done

# ---------------------------------------------------------------------------
# 自检
# ---------------------------------------------------------------------------
step "环境自检"

command -v docker >/dev/null 2>&1 || die "未安装 docker"
docker info >/dev/null 2>&1 || die "无法连接 Docker（当前用户不在 docker 组？或用 sudo 执行）"
ok "Docker 可用"

if [ "$MODE" = "renew" ]; then
    [ -d "$LE_HOST" ] || die "找不到 $LE_HOST，说明还没签过证书。先执行：bash $0 <域名> [邮箱]"
else
    [ -n "$DOMAIN" ] || die "缺少域名。用法：bash $0 <域名> [邮箱]"

    # 是否已按 HTTP 形态跑起来？签证书必须能通过 80 端口被外部访问到
    if docker ps --format '{{.Names}}' | grep -qx peakflow-nginx; then
        ok "peakflow-nginx 正在运行"
    else
        die "peakflow-nginx 没在运行。先执行（注意：此时 DOMAIN 还没证书，deploy.sh 不会叠加 HTTPS 层）：
    bash deploy/scripts/deploy.sh"
    fi

    # 80 端口对外可达性自检：在本机 curl 自己
    PORT="$(env_get NGINX_PORT || echo 80)"
    if curl -fsS -m 10 "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1; then
        ok "本机 :${PORT} 可访问"
    else
        warn "本机 :${PORT}/healthz 不通，nginx 可能没配好"
    fi

    # DNS 解析是否指向本机公网 IP —— 这是"签不过"最常见的原因
    step "DNS 自检"
    PUB_IP="$(curl -fsS -m 10 https://api.ipify.org 2>/dev/null || curl -fsS -m 10 https://ifconfig.me 2>/dev/null || echo '')"
    RESOLVED="$(getent hosts "$DOMAIN" 2>/dev/null | awk '{print $1; exit}' || true)"
    if [ -z "$RESOLVED" ]; then
        warn "$DOMAIN 在本机解析不出来（可能还没加 A 记录，或 DNS 未生效）"
    else
        ok "$DOMAIN → $RESOLVED"
        if [ -n "$PUB_IP" ]; then
            if [ "$RESOLVED" = "$PUB_IP" ]; then
                ok "与本机公网 IP（$PUB_IP）一致"
            else
                warn "本机公网 IP 是 $PUB_IP，与解析结果不一致"
                dim "若服务器在 NAT/负载均衡后面，这里不一致也可能正常；否则请先改 DNS。"
            fi
        fi
    fi
fi

mkdir -p "$WEBROOT_HOST" 2>/dev/null || sudo mkdir -p "$WEBROOT_HOST"
mkdir -p "$LE_HOST" 2>/dev/null || sudo mkdir -p "$LE_HOST"

# ---------------------------------------------------------------------------
# 执行
# ---------------------------------------------------------------------------
CERTBOT_ARGS=(
    --webroot -w /var/www/certbot
    --non-interactive
    --agree-tos
    --no-eff-email
    --keep-until-expiring
    --rsa-key-size 2048
)
[ "$STAGING" = "1" ] && CERTBOT_ARGS+=(--staging)

if [ "$MODE" = "renew" ]; then
    step "续期"
    dim "只对 30 天内到期的证书续期，未到期的会跳过"
    docker run --rm \
        -v "${WEBROOT_HOST}:/var/www/certbot" \
        -v "${LE_HOST}:/etc/letsencrypt" \
        "$CERTBOT_IMAGE" renew "${CERTBOT_ARGS[@]}" 2>&1 | sed 's/^/  /'

    if docker ps --format '{{.Names}}' | grep -qx peakflow-nginx; then
        step "重载 nginx 让新证书生效"
        docker exec peakflow-nginx nginx -s reload && ok "已 reload"
    fi
    ok "续期流程结束"
    exit 0
fi

step "签发证书：$DOMAIN（含 www.$DOMAIN）"
[ "$STAGING" = "1" ] && warn "staging 模式：签出来的证书浏览器不信任，仅用于验证流程与避免消耗正式配额"
dim "写 webroot：$WEBROOT_HOST"
dim "证书落地：$LE_HOST/live/$DOMAIN/"
echo ""

MAIL_ARGS=()
if [ -n "$EMAIL" ]; then
    MAIL_ARGS=(--email "$EMAIL")
else
    MAIL_ARGS=(--register-unsafely-without-email)
    warn "未提供邮箱，将不带邮箱注册（收不到到期告警）。建议补上第 2 个参数。"
fi

set +e
docker run --rm \
    -v "${WEBROOT_HOST}:/var/www/certbot" \
    -v "${LE_HOST}:/etc/letsencrypt" \
    "$CERTBOT_IMAGE" certonly "${CERTBOT_ARGS[@]}" "${MAIL_ARGS[@]}" \
    -d "$DOMAIN" -d "www.$DOMAIN" 2>&1 | sed 's/^/  /'
RC=${PIPESTATUS[0]}
set -e

if [ "$RC" != "0" ]; then
    cat <<EOF

$(echo -e "${C_RED}✗ 签发失败${C_OFF}")，按报错类型对照排查：

  1) "Timeout during connect" / "Connection refused"
     → 80 端口从公网到不了这台机器。检查：
         · 云控制台【安全组】是否放行 80（ufw 只是第二道，安全组是第一道）
         · 域名是否已备案（国内机房：未备案的 80/443 会被运营商拦截）
         · nginx 是否在跑：docker compose ps

  2) "DNS problem: NXDOMAIN" / "no A record"
     → $DOMAIN 还没有解析到本机公网 IP，或者解析还没生效（等几分钟到几小时）。

  3) "too many certificates already issued for exact set of domains"
     → 触发 Let's Encrypt 配额（同域名组每周 5 张）。
       先用 --staging 验证流程：bash $0 --staging $DOMAIN
       或者等一周。

  4) "unauthorized" 但 1)2) 都正常
     → 检查 nginx 是否正确暴露了 ACME 路径：
         curl -i http://$DOMAIN/.well-known/acme-challenge/test
       应返回 404（路径通了但没有这个文件），而不是 403/502/超时。

EOF
    exit 1
fi

step "签发成功"
ok "证书目录：$LE_HOST/live/$DOMAIN/"
ls -1 "$LE_HOST/live/$DOMAIN/" 2>/dev/null | sed 's/^/    /' || true
echo ""
echo -e "${C_YELLOW}下一步（必须做，否则 443 还是不通）：${C_OFF}"
echo "  重新部署，让 deploy.sh 检测到证书存在并自动叠加 HTTPS 层："
echo "      bash deploy/scripts/deploy.sh --no-build"
echo ""
echo -e "${C_DIM}自动续期（建议加进 crontab）${C_OFF}"
echo "  证书 90 天有效，Let's Encrypt 建议到期前 30 天续。每天跑一次即可："
echo "      (crontab -l 2>/dev/null; echo '30 3 * * * cd $ROOT && bash deploy/scripts/issue-cert.sh --renew >> /var/log/peakflow-certbot.log 2>&1') | crontab -"
echo ""
