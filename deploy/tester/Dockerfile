# ============================================================================
# PeakFlow 测试工具镜像（只在 compose profile=test 下使用）
# ----------------------------------------------------------------------------
# 构建（必须在项目根目录执行，因为要读 backend/pom.xml 预热 Maven 仓库）：
#   docker build -f deploy/tester/Dockerfile -t peakflow-tester:latest .
# 或直接：docker compose --profile test build tester
#
# 为什么单独做一个镜像，而不是把工具塞进 backend1：
#   backend1/backend2 是**生产运行镜像**，只需要 JRE + jar（约 230MB）。
#   把 JDK、Maven、Node、源码都装进去，两个实例都会涨到 1GB+，
#   而且生产运行镜像里带着构建工具本身就是坏味道（阶段 11「专业检测」会看这个）。
#
# 本镜像一次性提供容器内跑测试所需的全部工具：
#   JDK17 + Maven    → docker compose exec tester mvn test
#   newman (Node)    → docker compose exec -T -w /workspace/backend/postman tester \
#                        newman run PeakFlow-API.postman_collection.json \
#                        -e env-test.postman_environment.json --env-var baseUrl=http://nginx/api
#                      ⚠️ 必须在 postman/ 目录下跑：集合里 multipart 用例的 src 是相对路径
#                         （fixtures/test-avatar.png），newman 按「当前工作目录」解析它。
#                         不加 -w 会因找不到文件而让上传用例失败（业务码 5000）。
#                         详见 README.md §9.1 与 backend/postman/README.md
#   python3 + pymysql + bcrypt → 种子数据脚本 seed_base_data.py / setup_test_data.py
#   redis-tools      → 种子脚本清缓存要用的 redis-cli
#
# 镜像里不 COPY 源码：compose 把 ./backend 挂到 /workspace/backend，
# 这样改完代码直接重新 exec 即可，不必重建镜像。
# ============================================================================

# ---------------------------------------------------------------------------
# 阶段一：预热 Maven 本地仓库
#   `mvn test` 每次都要解析依赖，全量下载要好几分钟。这里把依赖固化进镜像，
#   容器内第一次跑测试就能直接用上，不用等。
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS deps

WORKDIR /build

# 与 backend/Dockerfile 保持同一套镜像源策略
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
RUN mkdir -p /root/.m2 \
 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml

COPY backend/pom.xml ./
# go-offline 会把 compile/runtime/test 三档依赖都拉下来
RUN mvn -B -ntp dependency:go-offline

# ---------------------------------------------------------------------------
# 阶段二：测试工具运行时
#   基础镜像直接复用 maven:3.9-eclipse-temurin-17（Ubuntu 24.04 + JDK17 + Maven 3.9），
#   这样 JDK 与 Maven 都来自本地已有镜像，不用再从 apt 装一套。
#   Node 走 apt（Ubuntu 24.04 自带 nodejs 18.19，满足 newman 6 要求的 node >= 16）。
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 国内直连 archive.ubuntu.com 很慢，构建时换成镜像站。
# Ubuntu 24.04 用的是 deb822 格式的 ubuntu.sources，旧版是 sources.list，两种都处理。
ARG APT_MIRROR=mirrors.aliyun.com
RUN set -eux; \
    for f in /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list; do \
      if [ -f "$f" ]; then \
        sed -i \
          -e "s|http://archive.ubuntu.com/ubuntu|https://${APT_MIRROR}/ubuntu|g" \
          -e "s|http://security.ubuntu.com/ubuntu|https://${APT_MIRROR}/ubuntu|g" \
          "$f"; \
      fi; \
    done

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      nodejs npm \
      python3 python3-pymysql python3-bcrypt \
      redis-tools \
      curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# newman：容器内跑 Postman 集合的命令行执行器
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry "$NPM_REGISTRY" \
 && npm install -g newman \
 && npm cache clean --force \
 && newman --version

# 把阶段一预热好的本地仓库搬进来
COPY --from=deps /root/.m2 /root/.m2

# compose 会把 ./backend 挂到这里
RUN mkdir -p /workspace/backend
WORKDIR /workspace/backend

# 容器常驻，等 exec 派活；业务镜像里绝不会有这个东西
CMD ["sleep", "infinity"]
