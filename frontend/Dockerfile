# ============================================================================
# PeakFlow 前端 · 多阶段构建
# ----------------------------------------------------------------------------
# 构建（在项目根目录执行）：
#   docker build -f frontend/Dockerfile -t peakflow-frontend:latest frontend
# 或直接 docker compose build frontend
#
# 构建上下文 = frontend/，对应忽略清单 frontend/.dockerignore。
# ============================================================================

# ---------------------------------------------------------------------------
# 阶段一：构建静态资源
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /build

# 先只拷依赖清单：源码改动不会导致重新装依赖
COPY package.json package-lock.json ./
# npm ci 严格按 lockfile 安装，保证镜像可复现（不要用 npm install）。
# 镜像源用国内站：容器内直连 registry.npmjs.org 很慢（lockfile 里已经是 npmmirror，
# 这里再兜一层，保证后续新包也走镜像站）
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry "$NPM_REGISTRY" && npm ci

COPY . .
# vite build 只做构建，单元/联调测试由 npm run test:xxx 单独负责
RUN npm run build

# ---------------------------------------------------------------------------
# 阶段二：静态托管
# ---------------------------------------------------------------------------
FROM nginx:1.27-alpine

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 站点配置：SPA 回退 + /api 反代到 backend:8080
COPY nginx/default.conf /etc/nginx/conf.d/default.conf

# Vite 产物
COPY --from=build /build/dist /usr/share/nginx/html

# nginx 官方镜像没带 curl，用 alpine 自带的 busybox wget。
# ⚠️ 必须写 127.0.0.1 而不是 localhost：本容器里 nginx 只监听 IPv4（0.0.0.0:80），
#    而 localhost 会优先解析成 IPv6 的 ::1 → Connection refused → 永远 unhealthy
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
