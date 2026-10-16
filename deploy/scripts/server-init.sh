#!/usr/bin/env bash
# ============================================================================
# PeakFlow · 云服务器初始化与安全加固（Ubuntu 22.04 / 24.04，root 执行一次）
# ----------------------------------------------------------------------------
# 这个脚本解决什么：一台刚买来的云服务器，默认状态是不适合直接放业务上线的 ——
#   允许 root 用密码 SSH 登录、没有防火墙、没有 swap、Docker 没装、
#   系统日志无限增长。本脚本把这些一次做完，并在每一步都做了「幂等」，
#   重复执行不会出错（第二次跑只是确认状态）。
#
# 用法（在服务器上以 root 身份执行）：
#   curl -fsSL ... # 不需要，直接用仓库里的这一份
#   sudo bash deploy/scripts/server-init.sh
#
# ⚠️ 执行前务必确认：你已能用【密钥】登录本机（阿里云控制台创建实例时选的密钥对）。
#    本脚本会关掉密码登录；如果密钥没生效，你会连不上（可用控制台 VNC 救回）。
# ============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# 0. 可调参数（用环境变量覆盖，不改本文件）
# ---------------------------------------------------------------------------
# 部署账号名：不允许 root 天天登录，日常操作走这个账号
DEPLOY_USER="${DEPLOY_USER:-peakflow}"
# 额外放行的端口（逗号分隔）。默认放 8080：
#   国内服务器未备案时 80 会被运营商拦，用「IP:8080」先把站点验收起来。
EXTRA_PORTS="${EXTRA_PORTS:-8080}"
# swap 大小：4G 内存的机器给 2G，防止内存尖峰触发 OOM Killer
SWAP_SIZE="${SWAP_SIZE:-2G}"
# Docker 镜像加速地址（空格分隔可写多个，Docker 按顺序回退）。
#   ⚠️ 必须配多个：实测 docker.m.daocloud.io 拉 redis 时会返回 502 Bad Gateway，
#      只配一个就会让整个部署失败。多个则自动回退。
#   ⚠️ 海外机器请显式传空串关掉：DOCKER_MIRROR="" —— 美国等地域直连 Docker Hub 最快。
DOCKER_MIRROR="${DOCKER_MIRROR-https://docker.1panel.live https://docker.m.daocloud.io https://docker.1ms.run https://dockerproxy.net}"

log()  { echo -e "\n\033[1;36m[peakflow-init]\033[0m $*"; }
warn() { echo -e "\033[1;33m[peakflow-init][警告]\033[0m $*"; }
die()  { echo -e "\033[1;31m[peakflow-init][失败]\033[0m $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
[ "$(id -u)" -eq 0 ] || die "请用 root 执行：sudo bash $0"
. /etc/os-release 2>/dev/null || die "读不到 /etc/os-release，本脚本只支持 Ubuntu"
case "$ID" in
    ubuntu) : ;;
    *) die "本脚本只针对 Ubuntu 编写，检测到 $ID。其他发行版请手工执行对应步骤。" ;;
esac
case "${VERSION_ID:-}" in
    22.04|24.04) : ;;
    *) warn "未在 Ubuntu ${VERSION_ID} 上验证过，继续执行但请留意报错。" ;;
esac

# ---------------------------------------------------------------------------
# 1. 时区与时间同步
#   为什么必须先做：应用里大量 LocalDateTime.now()，容器时区已固定为
#   Asia/Shanghai，宿主机若还是 UTC，日志时间与业务时间会差 8 小时，
#   排查问题时会看到"订单创建时间在未来"这种诡异现象。
#   时间不同步还会影响 HTTPS 证书校验、JWT 过期判断。
# ---------------------------------------------------------------------------
log "1/12 设置时区 Asia/Shanghai 并开启时间同步"
timedatectl set-timezone Asia/Shanghai
timedatectl set-ntp true
echo "  当前时间：$(date '+%F %T %Z')"

# ---------------------------------------------------------------------------
# 2. 基础软件
# ---------------------------------------------------------------------------
log "2/12 更新软件包索引并安装基础工具"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
    curl ca-certificates gnupg git unzip \
    ufw fail2ban unattended-upgrades \
    htop jq net-tools >/dev/null
echo "  git / curl / ufw / fail2ban / certbot(稍后) 等基础工具就绪"

# ---------------------------------------------------------------------------
# 3. Docker（引擎 + compose 插件）
#   为什么要用 apt 装而不是 get.docker.com 脚本：
#     apt 装的是发行版维护的包，能随系统一起被 unattended-upgrades 打安全补丁，
#     升级/卸载路径清晰；一键脚本装完就脱离包管理了。
#   为什么要用阿里云镜像源：大陆直连 download.docker.com 经常超时。
# ---------------------------------------------------------------------------
log "3/12 安装 Docker 引擎与 compose 插件"
if command -v docker >/dev/null 2>&1; then
    echo "  已安装：$(docker --version)，跳过安装"
else
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg \
        | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io \
        docker-buildx-plugin docker-compose-plugin >/dev/null
    echo "  安装完成：$(docker --version)"
fi
systemctl enable --now docker >/dev/null 2>&1

# Docker 守护进程配置
#   log-driver 上限：兜底。容器自身的 logging 上限写在 compose 里，
#       但万一有人手工 docker run，这一层还能挡一下，避免日志写满磁盘。
#   live-restore：docker 守护进程重启/升级时容器不跟着停，减少停机。
#   registry-mirrors：
#     · 国内机器必须配（直连 Docker Hub 基本不通）—— 而且【要配多个】，
#       单个加速站会 502（实测 docker.m.daocloud.io 拉 redis 时 502）。
#       Docker 会按顺序尝试，前一个失败自动回退到下一个。
#     · 海外机器（如美国弗吉尼亚）反而【不要配】：直连 Docker Hub 最快，
#       配了国内加速站会绕地球一圈，更慢。
#       用法：DOCKER_MIRROR="" bash server-init.sh
mkdir -p /etc/docker
if [ -n "${DOCKER_MIRROR}" ]; then
    MIRRORS_JSON="$(printf '"%s", ' $DOCKER_MIRROR | sed 's/, $//')"
    cat > /etc/docker/daemon.json <<JSON
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" },
  "live-restore": true,
  "registry-mirrors": [${MIRRORS_JSON}]
}
JSON
    echo "  daemon.json 已写入（日志上限 10m×3、live-restore、镜像加速：${DOCKER_MIRROR}）"
else
    cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" },
  "live-restore": true
}
JSON
    echo "  daemon.json 已写入（日志上限 10m×3、live-restore；未配镜像加速 = 直连 Docker Hub，适用于海外机器）"
fi
systemctl restart docker
docker compose version

# ---------------------------------------------------------------------------
# 4. 部署账号（不再用 root 日常操作）
#   为什么：
#     · 日常操作犯错（比如 rm 打错路径）时，非 root 账号是第一道保险；
#     · 审计上能分清"是人操作还是系统操作"；
#     · 这个账号加入 docker 组 —— 注意这等于给了它 root 级权限（能挂载宿主目录），
#       所以它是"运维账号"，不是"受限账号"，只是比直接用 root 好。
# ---------------------------------------------------------------------------
log "4/12 创建部署账号 ${DEPLOY_USER}"
if id "$DEPLOY_USER" >/dev/null 2>&1; then
    echo "  账号已存在，跳过创建"
else
    adduser --disabled-password --gecos "" "$DEPLOY_USER"
    echo "  已创建（无密码，只能密钥登录）"
fi
usermod -aG sudo,docker "$DEPLOY_USER"

# 把 root 的 SSH 公钥复制过去，这样同一个密钥既能登 root 也能登部署账号
if [ -f /root/.ssh/authorized_keys ]; then
    install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/${DEPLOY_USER}/.ssh"
    cp /root/.ssh/authorized_keys "/home/${DEPLOY_USER}/.ssh/authorized_keys"
    chown "${DEPLOY_USER}:${DEPLOY_USER}" "/home/${DEPLOY_USER}/.ssh/authorized_keys"
    chmod 600 "/home/${DEPLOY_USER}/.ssh/authorized_keys"
    echo "  已复制 root 的 authorized_keys 到 ${DEPLOY_USER}"
else
    warn "root 下没有 authorized_keys，${DEPLOY_USER} 暂时无法密钥登录"
fi

# 免密 sudo：该账号没有设置密码，不给 NOPASSWD 就没法执行 sudo。
# 安全性依赖"密钥不外泄"，与云厂商推荐的部署账号做法一致。
echo "${DEPLOY_USER} ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/90-${DEPLOY_USER}"
chmod 440 "/etc/sudoers.d/90-${DEPLOY_USER}"
visudo -cf "/etc/sudoers.d/90-${DEPLOY_USER}" >/dev/null
echo "  已配置免密 sudo（/etc/sudoers.d/90-${DEPLOY_USER}）"

# ---------------------------------------------------------------------------
# 5. SSH 加固
#   ⚠️ 这里最危险的坑：Ubuntu 云镜像里有 /etc/ssh/sshd_config.d/50-cloud-init.conf，
#      它通常写着 PasswordAuthentication yes，而 sshd 是「先读到的值生效」。
#      所以我们的文件名必须排在它【前面】（00- 开头），否则设置会被它盖掉。
#      为了双保险，下面还会把那个文件里相关的行注释掉。
#   ⚠️ 必须 sshd -t 校验通过才 reload，否则一个语法错误就会让你再也连不上。
# ---------------------------------------------------------------------------
log "5/12 SSH 安全加固"
cat > /etc/ssh/sshd_config.d/00-peakflow-hardening.conf <<'SSHD'
# —— PeakFlow 部署加固（由 deploy/scripts/server-init.sh 生成）——
# 允许 root 用【密钥】登录，但禁止密码。之所以不彻底关掉 root 登录：
# 万一部署账号配错了，还能用 root + 密钥进来救，降低把自己锁在门外的风险。
PermitRootLogin prohibit-password
# 彻底禁止密码登录：公网 22 端口每天都会被扫描器撞密码，密码是最弱的一环
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
# 每连接最多试 3 次（默认 6），配合 fail2ban 大幅降低爆破成功率
MaxAuthTries 3
MaxSessions 5
# 闲置 5 分钟无响应即断开，防止半开连接堆积
ClientAliveInterval 300
ClientAliveCountMax 2
# 生产机不需要图形/代理转发，关掉即减少攻击面
X11Forwarding no
AllowAgentForwarding no
AllowTcpForwarding no
# 记录密钥指纹级日志，便于事后追溯谁登过
LogLevel VERBOSE
SSHD

# 双保险：把云镜像预置文件里会覆盖我们的那几行注释掉
CLOUD_SSHD=/etc/ssh/sshd_config.d/50-cloud-init.conf
if [ -f "$CLOUD_SSHD" ]; then
    sed -i -E 's/^[[:space:]]*(PasswordAuthentication|PermitRootLogin|KbdInteractiveAuthentication)/# [peakflow-init] \1/' "$CLOUD_SSHD"
    echo "  已注释 $CLOUD_SSHD 中会覆盖加固项的设置"
fi

sshd -t || die "sshd 配置校验失败，已放弃 reload（配置未生效，你仍可正常连接）"
systemctl reload ssh 2>/dev/null || systemctl reload sshd
echo "  SSH 已加固：禁密码登录 / root 仅密钥 / 每连接最多试 3 次"
warn "从现在起，请改用【密钥】登录，命令示例：ssh ${DEPLOY_USER}@<你的公网IP>"

# ---------------------------------------------------------------------------
# 6. 防火墙（ufw）
#   先放行 22 再 enable —— 顺序反了会把自己关在门外。
#   ⚠️ 需要知道的真相：Docker 发布端口是通过 iptables 的 DOCKER 链实现的，
#      它会绕过 ufw 的规则。所以「哪些端口真正对公网开放」最终由
#      docker-compose 里的 ports 决定。ufw 在这里的价值是：
#      挡住非 Docker 的进程（例如你临时装的软件）意外对外监听。
# ---------------------------------------------------------------------------
log "6/12 配置防火墙 ufw"
ufw --force reset >/dev/null
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp comment 'SSH' >/dev/null
ufw allow 80/tcp comment 'HTTP' >/dev/null
ufw allow 443/tcp comment 'HTTPS' >/dev/null
if [ -n "$EXTRA_PORTS" ]; then
    IFS=',' read -ra PORTS <<< "$EXTRA_PORTS"
    for p in "${PORTS[@]}"; do
        p="$(echo "$p" | tr -d ' ')"
        [ -n "$p" ] || continue
        ufw allow "${p}/tcp" comment "extra" >/dev/null
        echo "  额外放行 ${p}/tcp"
    done
fi
# ⚠️⚠️ 关键保险：启用防火墙【之前】必须确认 22 规则真的进去了。
#   踩过的坑：脚本在 ufw 段被中断过，结果 ufw 处于「已启用 + 只有默认拒绝、
#   没有放行 22」的状态 ⇒ SSH 从外面完全连不上（表现为超时），
#   而且 ping 仍然是通的（ufw 的 before.rules 默认放行 ICMP echo），
#   极易被误判成"安全组问题"。远程排查绕了很久。
#   所以：宁可让防火墙保持关闭（能进去修），也不能带着不完整规则启用。
if ufw show added 2>/dev/null | grep -qE 'allow .*22/tcp|allow 22/tcp'; then
    ufw --force enable >/dev/null
    echo "  ✓ 已确认 22 规则存在，防火墙已启用"
else
    die "ufw 未成功写入 22/tcp 规则，已【放弃启用】防火墙（保持关闭状态，避免把远程连接锁死）。
     请手工检查 ufw 是否正常：ufw show added / ufw status"
fi
# 启用后再验一次：确认 22 确实在生效规则里
ufw status | grep -q '22/tcp' || die "ufw 已启用但 22/tcp 不在生效规则中，请手工检查！"
ufw status numbered | sed 's/^/  /'

# ---------------------------------------------------------------------------
# 7. fail2ban：把反复试错的 IP 直接关进小黑屋
#   与 SSH 的 MaxAuthTries 是两层：MaxAuthTries 是单连接，fail2ban 是累计。
# ---------------------------------------------------------------------------
log "7/12 配置 fail2ban"
cat > /etc/fail2ban/jail.d/peakflow.local <<'F2B'
[DEFAULT]
# 关多久：1 小时
bantime  = 1h
# 统计窗口：10 分钟
findtime = 10m
# 触发阈值
maxretry = 5
# 用 nftables/iptables 的默认动作即可
backend  = systemd

[sshd]
enabled  = true
port     = 22
maxretry = 4
F2B
systemctl enable --now fail2ban >/dev/null 2>&1
systemctl restart fail2ban
sleep 1
fail2ban-client status sshd 2>/dev/null | sed 's/^/  /' || warn "fail2ban 状态查询失败，稍后用 systemctl status fail2ban 查看"

# ---------------------------------------------------------------------------
# 8. swap
#   为什么小机器必须要：4G 内存同时跑 MySQL + Redis + RocketMQ + JVM，
#   常态约 2.5~3G，但发版/GC/导入数据时会出现瞬时尖峰。
#   没有 swap，尖峰的直接后果是 OOM Killer 随机杀进程（通常是杀 MySQL，最惨）。
#   有了 swap 只会变慢，不会崩。
#   vm.swappiness=10：尽量别用 swap（毕竟慢），只在真的不够时才用。
# ---------------------------------------------------------------------------
log "8/12 创建 ${SWAP_SIZE} swap"
if swapon --show | grep -q '/swapfile'; then
    echo "  swapfile 已存在：$(swapon --show=size --noheadings | tr -d ' ')"
else
    fallocate -l "$SWAP_SIZE" /swapfile
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
    swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
    echo "  已创建并写入 /etc/fstab（重启后仍生效）"
fi

# ---------------------------------------------------------------------------
# 9. 内核参数
# ---------------------------------------------------------------------------
log "9/12 写入内核参数"
cat > /etc/sysctl.d/99-peakflow.conf <<'SYSCTL'
# —— PeakFlow 生产调优（由 deploy/scripts/server-init.sh 生成）——

# Redis 官方要求：允许内存过量分配，否则 fork（AOF/RDB 持久化）可能失败
vm.overcommit_memory = 1
# 尽量不使用 swap，只在内存真正不足时才用
vm.swappiness = 10

# 半连接队列与已完成队列上限。PHP/Java 类应用在并发上来时常见的
# "连接被重置"多半是这两个队列满导致的
net.core.somaxconn = 2048
net.ipv4.tcp_max_syn_backlog = 4096
# TIME_WAIT 状态快速回收复用（解决端口耗尽）
net.ipv4.tcp_fin_timeout = 15
net.ipv4.tcp_tw_reuse = 1
net.ipv4.ip_local_port_range = 10240 65000

# 文件句柄上限（容器里跑 JVM + MySQL 都吃这个）
fs.file-max = 1000000
fs.inotify.max_user_watches = 524288

# BBR 拥塞控制：比默认 cubic 在高丢包链路上吞吐更好，国内公网尤其明显
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
SYSCTL
sysctl --system >/dev/null
echo "  BBR 状态：$(sysctl -n net.ipv4.tcp_congestion_control)"

# ---------------------------------------------------------------------------
# 10. 系统日志上限
#     journald 默认可以吃掉磁盘的一大块（一般是总容量的 10%）。
#     小磁盘机器上必须收紧，否则某天 docker/内核刷日志就能把 40G 盘写满。
# ---------------------------------------------------------------------------
log "10/12 限制 systemd 日志占用"
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/99-peakflow.conf <<'JOURNAL'
[Journal]
# 日志总量上限 200MB，单文件 50MB
SystemMaxUse=200M
SystemMaxFileSize=50M
# 只保留 2 周
MaxRetentionSec=2week
JOURNAL
systemctl restart systemd-journald
echo "  已限制为最多 200MB / 保留 2 周"

# ---------------------------------------------------------------------------
# 11. 自动安全更新 + certbot
#    只自动装【安全补丁】，不自动升级大版本 —— 避免某次自动升级把
#    MySQL/Docker 的版本换掉导致业务起不来。
# ---------------------------------------------------------------------------
log "11/12 开启自动安全更新并安装 certbot"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'APT'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
APT
# 只勾 security 源
cat > /etc/apt/apt.conf.d/52peakflow-unattended.conf <<'APT2'
Unattended-Upgrade::Allowed-Origins {
    "${distro_id}:${distro_codename}-security";
};
Unattended-Upgrade::AutoFixInterruptedDpkg "true";
Unattended-Upgrade::MinimalSteps "true";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
// 需要重启内核时不要自动重启（发版窗口我们说了算）
Unattended-Upgrade::Automatic-Reboot "false";
APT2
apt-get install -y -qq certbot >/dev/null
echo "  certbot 版本：$(certbot --version 2>&1)"

# ---------------------------------------------------------------------------
# 12. 汇总
# ---------------------------------------------------------------------------
log "12/12 初始化完成，当前状态"
cat <<SUMMARY

  ── 系统 ─────────────────────────────────────────────
  发行版      : $(. /etc/os-release && echo "$PRETTY_NAME")
  内核        : $(uname -r)
  时区/时间   : $(date '+%F %T %Z')
  CPU / 内存  : $(nproc) 核 / $(free -h | awk '/^Mem:/{print $2}')（可用 $(free -h | awk '/^Mem:/{print $7}')）
  swap        : $(free -h | awk '/^Swap:/{print $2}')
  磁盘        : $(df -h / | awk 'NR==2{print $2" 总量，已用 "$5}')

  ── 安全 ─────────────────────────────────────────────
  SSH 密码登录: 已禁止（只能用密钥）
  root 登录   : 仅密钥
  ufw 放行    : $(ufw status | grep -E '^[0-9]' | awk '{printf "%s ", $1}')
  fail2ban    : $(systemctl is-active fail2ban)
  自动安全更新: 已开启
  容器日志上限: 10MB × 3

  ── 下一步 ───────────────────────────────────────────
  1) 退出当前会话，改用密钥登录部署账号：
       ssh -i <你的私钥> ${DEPLOY_USER}@<公网IP>
  2) 克隆代码到 /opt/peakflow 并生成生产环境变量：
       sudo mkdir -p /opt && cd /opt
       sudo git clone <你的仓库地址> peakflow
       cd peakflow && sudo bash deploy/scripts/gen-prod-env.sh <对外地址> <端口>
  3) 启动：
       sudo bash deploy/scripts/deploy.sh

SUMMARY
