# ============================================================================
# PeakFlow 后端 · 多阶段构建
# ----------------------------------------------------------------------------
# 构建（在项目根目录执行）：
#   docker build -f backend/Dockerfile -t peakflow-backend:latest backend
# 或直接 docker compose build backend
#
# 关于构建上下文：context 必须指向 backend/，因为 Maven 工程根在那儿。
# 对应的 .dockerignore 是 backend/.dockerignore。
# ============================================================================

# ---------------------------------------------------------------------------
# 阶段一：构建 jar
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 容器内直连 repo.maven.apache.org 在国内极慢甚至超时，构建前先换镜像源。
# 用 ARG 暴露出来，换环境时 docker build --build-arg MAVEN_MIRROR_URL=... 即可，
# 不改 Dockerfile 也能切换。
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
RUN mkdir -p /root/.m2 \
 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml

# 先只拷 pom.xml 并预拉依赖：只要 pom 没变，这一层就命中缓存，
# 后续改 Java 代码不会重新下载几百 MB 依赖
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# 跳过测试：镜像构建只负责「能不能编译打包」，
# 测试由 CI 的 mvn clean verify 单独负责，不必在这里重复跑（也避免构建依赖中间件）
RUN mvn -B -ntp clean package -DskipTests \
    && cp target/*.jar /build/app.jar

# ---------------------------------------------------------------------------
# 阶段二：运行
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

# 时区：应用里大量 LocalDateTime.now()，容器默认 UTC 会让秒杀时间窗口整体偏移 8 小时
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 非 root 运行，降低容器逃逸后的影响面
RUN groupadd --system --gid 1001 peakflow \
 && useradd  --system --uid 1001 --gid peakflow --create-home peakflow

WORKDIR /app
COPY --from=build --chown=peakflow:peakflow /build/app.jar /app/app.jar

# 日志目录（logging.file.path 默认 ./logs/{env}）
# 本地降级上传目录（未配 OSS 时 AliOssUtil 写这里，见 app.upload.local-dir）
RUN mkdir -p /app/logs /app/uploads && chown -R peakflow:peakflow /app/logs /app/uploads
VOLUME ["/app/logs", "/app/uploads"]

USER peakflow
EXPOSE 8080

# MaxRAMPercentage 让 JVM 按容器内存上限算堆，而不是按宿主机内存
# （不加的话容器里看到的仍是宿主机总内存，容易被 OOMKilled）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -Djava.security.egd=file:/dev/./urandom"

# exec 形式 + sh -c 是为了让 $JAVA_OPTS 能被展开；
# 用 exec 保证 java 是 PID 1，能收到 docker stop 的 SIGTERM 优雅停机
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
