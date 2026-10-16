#!/bin/bash
# ============================================================================
# 在「Docker Hub 不可达」的网络环境下拉取 PeakFlow 所需镜像
# ----------------------------------------------------------------------------
# 背景：本机直连 registry-1.docker.io 超时、经沙箱代理返回 502，
#       但国内镜像站可达（实测 docker.m.daocloud.io 返回 HTTP 401 = 正常 registry 响应）。
#
# 做法：先从镜像站按 <mirror>/<image> 拉取，再 retag 成【官方规范名】。
#   ⇒ 好处：docker-compose.yml 与 Dockerfile 里写的都是 mysql:8.0 这种规范名，
#           换到网络正常的机器可直接用，不把镜像站地址写进仓库。
# ============================================================================
set -uo pipefail

MIRROR="${PEAKFLOW_DOCKER_MIRROR:-docker.m.daocloud.io}"

# 规范名列表（与 docker-compose.yml / Dockerfile 中的引用保持一致）
IMAGES=(
    "mysql:8.0"
    "redis:7-alpine"
    "apache/rocketmq:4.9.7"
    "maven:3.9-eclipse-temurin-17"
    "eclipse-temurin:17-jre-jammy"
    "node:22-alpine"
    "nginx:1.27-alpine"
)

fail=0
for img in "${IMAGES[@]}"; do
    # Docker Hub 官方镜像（无斜杠）要补 library/ 前缀，带命名空间的不用
    if [[ "$img" == */* ]]; then
        src="$MIRROR/$img"
    else
        src="$MIRROR/library/$img"
    fi

    printf '%-42s ' "$img"
    if docker image inspect "$img" >/dev/null 2>&1; then
        echo "已存在，跳过"
        continue
    fi

    if docker pull -q "$src" >/dev/null 2>&1; then
        docker tag "$src" "$img"
        echo "OK（来自 $src）"
    else
        echo "失败（$src）"
        fail=1
    fi
done

echo
if [ "$fail" -eq 0 ]; then
    echo "全部镜像就绪："
else
    echo "存在拉取失败的镜像，请检查网络或更换 PEAKFLOW_DOCKER_MIRROR" >&2
fi
docker images --format '  {{.Repository}}:{{.Tag}}  {{.Size}}' | sort
exit "$fail"
