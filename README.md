# PeakFlow · 高并发秒杀商城

> 一个以**秒杀下单**为核心链路的电商全栈项目：购买权门闩 + Redis Lua 原子预扣 + RocketMQ 异步落库 + DB 唯一索引兜底，
> 四层防线抗住瞬时高并发并杜绝超卖；另配齐订单状态机、支付资金闭环、售后退款状态机与 RBAC 权限体系。
> 前后端分离，含 123 请求 / 768 断言的 Postman 全链路测试套件。

<p align="left">
  <img alt="Java" src="https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-2.7.18-6DB33F?logo=springboot&logoColor=white">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3.4-42B883?logo=vuedotjs&logoColor=white">
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white">
  <img alt="Redis" src="https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white">
  <img alt="RocketMQ" src="https://img.shields.io/badge/RocketMQ-4.9.7-2C3E50">
</p>

---

## 目录

- [一、这是什么项目](#一这是什么项目)
- [二、功能模块](#二功能模块)
- [三、技术栈](#三技术栈)
- [四、系统架构](#四系统架构)
- [五、目录结构](#五目录结构)
- [六、快速开始](#六快速开始)
- [七、环境变量与三套环境](#七环境变量与三套环境)
- [八、演示账号](#八演示账号)
- [九、测试](#九测试)
- [十、关键设计说明](#十关键设计说明)
- [十一、文档索引](#十一文档索引)
- [十二、已知事项](#十二已知事项)

---

## 一、这是什么项目

PeakFlow 是一个**前后端分离的秒杀商城主站**，包含三条完整业务闭环：

| 闭环 | 链路 | 难点 |
|---|---|---|
| **秒杀抢购** | 预热 → 抢购（返回 requestId）→ 轮询结果 → 生成订单 | 超卖、一人一单、MQ 故障降级 |
| **支付资金** | 下单 → 预支付 → 第三方收银台 → 异步回调入账 → 订单已付款 | 验签、金额篡改、回调幂等、登出后旧 token 失效 |
| **退款售后** | 申请 → 管理员审核 → 发起第三方退款 → 回调 → 订单已退款 | 状态机 CAS、金额比对、中态超时收敛、部分失败可重试 |

设计上刻意**把「资金」与「库存」当成一等公民**：所有涉及钱和库存的状态流转都走乐观锁 CAS 或数据库唯一索引兜底，
不依赖 Redis 缓存（缓存会重启、会过期、会被清空）。

---

## 二、功能模块

### 商城端（`/`）

| 模块 | 页面 | 说明 |
|---|---|---|
| 首页 / 商品 | `/`、`/products`、`/product/:id` | 商品分页、分类筛选、详情 |
| **秒杀专区** | `/seckill` | 只展示「有效」秒杀商品（上架 + 活动窗口内 + 有秒杀库存），抢购后轮询结果 |
| 购物车 | `/cart` | Redis Hash 存储，勾选态前端维护 |
| 结算下单 | `/checkout` | 地址选择、多商品、下单风控配额校验 |
| 订单中心 | `/orders`、`/order/:id` | 订单列表/详情、取消、支付、确认收货、申请售后 |
| 地址管理 | `/address` | 增删改查 + 默认地址 |
| 个人中心 | `/user` | 改昵称、改密码、换头像、登录日志 |

### 管理后台（`/admin`）

| 模块 | 页面 | 所需权限 |
|---|---|---|
| 数据看板 | `/admin` | `stats:view` |
| 用户管理 | `/admin/users` | `user:manage` |
| 商品管理 | `/admin/products` | `product:manage` |
| 分类管理 | `/admin/categories` | `category:manage` |
| 秒杀管理 | `/admin/seckill` | `seckill:manage` |
| 订单管理 | `/admin/orders` | `order:read` / `order:ship` |
| 退款工作台 | `/admin/refunds` | `refund:read` / `refund:audit` / `refund:close` |

### 模拟第三方支付（MockPay）

项目内置一个**扮演支付宝角色**的模拟支付平台（`/mock-pay/**`），让资金闭环可以完全在本地跑通、不依赖任何真实支付渠道：

- `GET /mock-pay/cashier` —— 收银台页面（返回真实 HTML，保留 URL 查询参数）
- `POST /mock-pay/pay` —— 收款并**自动回调**商户 `/pay/notify`
- `POST /mock-pay/refund` —— 收款退款并回调商户 `/refund/notify`

可通过 `mock-pay.fail-rate` / `mock-pay.refund-fail-rate`（0–100）注入失败，用来验证
「回调失败时订单不会被误改成已付款」「退款失败 → 管理员重试」等异常分支。

---

## 三、技术栈

### 后端

| 类别 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 2.7.18、Spring MVC、Spring AOP |
| 持久层 | MyBatis-Plus 3.5.3.1（逻辑删除、乐观锁 `@Version`）、MySQL 8.0、HikariCP |
| 缓存 / 分布式锁 | Redis 7（Lettuce 连接池）+ Redisson 3.17.7 |
| 消息队列 | RocketMQ 4.9.7（秒杀异步落库、订单超时延迟消息） |
| 安全 | Spring Security + JWT（jjwt 0.11.5）、BCrypt、RBAC 方法级鉴权 `@PreAuthorize` |
| 接口文档 | springdoc-openapi 1.8.0（Swagger UI / `/doc.html`） |
| 可观测性 | Spring Boot Actuator + Micrometer/Prometheus、SkyWalking Logback 工具包（`%tid` 链路 ID）、自建 `X-Trace-Id` 响应头 |
| 对象存储 | 阿里云 OSS SDK 3.17.4 |
| 构建 | Maven Wrapper（`mvnw` / `mvnw.cmd`，无需预装 Maven） |

### 前端

| 类别 | 选型 |
|---|---|
| 框架 | Vue 3.4（Composition API） |
| 构建 | Vite 5.3 |
| UI | Element Plus 2.7 + `@element-plus/icons-vue` |
| 状态管理 | Pinia 2.1 |
| 路由 | Vue Router 4.3（导航守卫拦截登录态 / 管理员态） |
| HTTP | axios 1.7（统一拦截器：自动注入 JWT、统一错误提示、401 静默跳登录） |
| 测试 | Vitest 2.1 + `@vue/test-utils` + MSW 2.7 + happy-dom 15.11 |

---

## 四、系统架构

```mermaid
flowchart TB
    subgraph Client["客户端"]
        B["浏览器 / Postman"]
    end

    subgraph FE["前端 · Vue 3 + Vite :3000"]
        V["商城端 + 管理后台<br/>axios baseURL=/api"]
    end

    subgraph BE["后端 · Spring Boot :8080"]
        F["TraceIdFilter<br/>JwtAuthenticationFilter"]
        C["Controller 层<br/>13 个控制器 / 71 个端点"]
        S["Service 层<br/>订单/支付/退款/秒杀"]
        SEC["Spring Security<br/>RBAC @PreAuthorize"]
    end

    subgraph MW["中间件（Docker）"]
        MY["MySQL 8.0 :3307<br/>dev/test/prod 三库"]
        RD["Redis :6380<br/>库存/缓存/黑名单"]
        MQ["RocketMQ :9876<br/>秒杀/超时"}
    end

    MOCK["MockPay<br/>模拟支付宝"]

    B --> V
    B -->|"直连 /api（Postman）"| F
    V -->|"proxy /api → :8080"| F
    F --> SEC --> C --> S
    S --> MY
    S --> RD
    S --> MQ
    S -.->|"发起支付 / 退款"| MOCK
    MOCK -.->|"异步回调 /pay/notify<br/>/refund/notify"| F

    MY -.->|"超时延迟消息"| MQ
    MQ -.->|"异步创建订单"| S
```

**请求链路说明**

1. 前端 dev 端口 `3000`，`vite.config.js` 把 `/api` 前缀重写后代理到 `localhost:8080`，
   因此后端控制器**不带 `/api` 前缀**（如前端调 `/api/auth/login` → 后端 `/auth/login`）。
2. 后端入口先过 `TraceIdFilter`（生成 traceId 写入 MDC 与响应头 `X-Trace-Id`），
   再过 `JwtAuthenticationFilter`（校验 token、比对 Redis 黑名单）。
3. 鉴权分两层：白名单（`security.white-list` + `SecurityConfig`）放行公开接口，
   其余接口经 Spring Security 认证后，再由方法级 `@PreAuthorize("hasAuthority('xxx')")` 校验权限点。

---

## 五、目录结构

```
PeakFlow/
├── docker-compose.yml              # 中间件 + 后端双实例 + 前端 Nginx，一条命令起全套
├── .env.example                    # 环境变量模板（本文件说明见第七节）
├── docs/API.md                     # 接口文档（71 个端点总览）
├── backend/                        # Spring Boot 后端
│   ├── Dockerfile / .dockerignore  # 多阶段构建（maven 打包 → temurin JRE 运行）
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd             # Maven Wrapper
│   ├── postman/                    # 接口测试套件（123 请求 / 768 断言）+ 权威测试规范
│   └── src/
│       ├── main/java/com/zhou/peakflow/
│       │   ├── controller/         # 13 个控制器，共 68 个业务端点
│       │   ├── service/ + impl/    # 业务逻辑（含 TxService 拆分的独立事务边界）
│       │   ├── mapper/             # MyBatis-Plus Mapper
│       │   ├── pojo/ dto/ vo/      # 实体 / 入参 / 出参
│       │   ├── enums/              # ResultCode、OrderStatus、RefundStatus 等
│       │   ├── config/             # Security、CORS、Redis、线程池、TraceId 等 15 个配置类
│       │   ├── security/           # JWT 过滤器与工具
│       │   ├── consumer/           # RocketMQ 消费者（含死信处理）
│       │   ├── task/               # 定时扫表（订单超时、退款中态收敛）
│       │   ├── mockpay/            # 模拟第三方支付平台
│       │   ├── guard/              # 商品操作前置校验（在途订单 / 未完结退款）
│       │   ├── exception/          # BusinessException + 全局异常处理器
│       │   └── utils/              # JWT、签名、雪花 ID、脱敏、OSS 等
│       ├── main/resources/
│       │   ├── application.yml     # 公共配置
│       │   ├── application-dev.yml # 开发环境覆盖
│       │   ├── application-test.yml
│       │   ├── application-prod.yml# 生产环境覆盖（安全收紧）
│       │   ├── logback-spring.xml
│       │   ├── lua/                # 秒杀库存扣减 / 回补 / 重置脚本
│       │   ├── mapper/             # MyBatis XML
│       │   └── static/             # MockPay 收银台页面
│       └── test/                   # 单元测试
├── deploy/
│   ├── mysql/
│   │   ├── init/                   # 00 建三库 / 01 表结构 / 02 基础数据 / 03 商品目录 / 04 演示账号 / 05 灌 test+prod
│   │   ├── prod/grant-admin-role.sql # 生产账号授权（按用户名赋角色，4 个角色通用）
│   │   └── README.md               # 数据库初始化权威说明
│   ├── nginx/default.conf          # 宿主机 Nginx 方案（upstream 指向 127.0.0.1:8080/8081）
│   ├── rocketmq/broker.conf        # brokerIP1=127.0.0.1（不配会注册容器内网 IP）
│   └── scripts/pull-images.sh      # 国内网络拉镜像（镜像站 → retag 成规范名）
└── frontend/                       # Vue 3 前端
    ├── Dockerfile / .dockerignore  # 多阶段构建（node 打包 → nginx:alpine 托管）
    ├── nginx/default.conf          # 容器内反代 + 负载均衡（upstream backend1/backend2）
    ├── vite.config.js              # dev 端口 3000、/api 代理
    ├── package.json
    ├── src/
    │   ├── api.js                  # 全部接口定义 + axios 拦截器
    │   ├── router.js               # 路由与守卫（requiresAuth / requiresAdmin）
    │   ├── stores/                 # Pinia（auth / cart 等）
    │   ├── views/mall/             # 商城端 11 个页面
    │   ├── views/admin/            # 管理后台 8 个页面
    │   └── layouts/ components/ utils/ constants/ styles/
    └── test/                       # Vitest 单测 / 集成 / 回归
```

---

## 六、快速开始

### 6.1 前置条件

| 组件 | 版本 | 端口 |
|---|---|---|
| JDK | 17 | — |
| Node.js | ≥ 18 | — |
| MySQL | 8.0 | **3307**（见下方说明） |
| Redis | 7（或 6+） | 6380 |
| RocketMQ | 4.9.7（NameServer + Broker） | 9876 / 10911 |

中间件与前后端已全部编排进 `docker-compose.yml`：

```bash
# 只起中间件
docker compose up -d mysql redis namesrv broker

# 起整套（中间件 + 后端双实例 backend1/backend2 + 前端 nginx）
docker compose up -d

# 看状态：全部应为 healthy
docker compose ps
```

> **为什么 MySQL 是 3307 而不是 3306**：本机 3306 常被「Windows 服务 MySQL80」占用，
> 且那个实例里往往存着别的项目的库。PeakFlow 的容器映射到 **3307** 避免互相干扰。
> Redis 用 **6380** 而非默认 6379 也是同样的理由。
>
> **RocketMQ 的 `brokerIP1` 是关键项**：`deploy/rocketmq/broker.conf` 里配成 `127.0.0.1`，
> 不配的话 broker 会把容器内网 IP（如 172.x）注册到 NameServer，
> 宿主机上的应用拿到后连不上，表现为「秒杀能跑但没走 MQ、实际走了降级同步落库」。
>
> 国内网络拉镜像：`./deploy/scripts/pull-images.sh`（先从镜像站拉再 retag 成规范名）。

### 6.2 初始化数据库（容器化后自动完成）

用 compose 起 MySQL 时，`deploy/mysql/init/` 下的脚本会按**文件名字典序自动执行**：

| 文件 | 作用 |
|---|---|
| `00-create-databases.sql` | 建 `peakflow_dev` / `peakflow_test` / `peakflow_prod` 三个库 |
| `01-schema.sql` ~ `04-demo-account.sql` | 灌进 `MYSQL_DATABASE`（固定为 `peakflow_dev`） |
| `05-provision-envs.sh` | 再把 `01~03` 灌进 test / prod |

```bash
docker compose up -d mysql
# 应看到 peakflow_dev / peakflow_test / peakflow_prod 三个库
docker exec peakflow-mysql mysql -uroot -p1234 -e "SHOW DATABASES;"
```

> 手工灌库（不走容器）也可以，但 `01~04` 脚本里**没有 `USE` 语句**，
> 必须在命令行显式指定目标库，否则会灌进当前默认库。
> `04-demo-account.sql` 含**密码公开**的演示账号，**生产环境严禁加载**。
> 详细说明（生产环境的账号创建与授权方式、秒杀时间窗口为何用相对时间）见 [`deploy/mysql/README.md`](deploy/mysql/README.md)。

### 6.3 配置环境变量

```bash
cp .env.example .env
# 编辑 .env：至少填 JWT_SECRET（≥32 字节随机串）、DB_USERNAME / DB_PASSWORD
```

> ⚠️ 项目**未引入 spring-dotenv**，Spring Boot 不会自动读取 `.env` 文件。
> 需按下面三种方式之一把变量注入**进程环境**：IDE 的 Run Configuration、终端 `set -a; source .env; set +a`、
> 或 Docker Compose 的 `env_file`。`.gitignore` 已就位：`.env`、`logs/`、`target/`、
> `node_modules/`、`uploads/`（未配 OSS 时的本地降级上传目录）都已加入忽略。

### 6.4 启动后端

```bash
cd backend

# 方式一：Maven Wrapper（Windows 用 mvnw.cmd）
./mvnw clean package -DskipTests
java -jar target/PeakFlow-1.0-SNAPSHOT.jar --spring.profiles.active=dev

# 方式二：直接跑（IDE 里运行 Application.java，VM options 加 -Dspring.profiles.active=dev）
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 方式三：容器（多实例，推荐）
cd ..
docker compose up -d backend1 backend2
```

> ⚠️ 本机直跑时若端口不是 8080，先看环境里有没有 `SERVER_PORT`（部分沙箱/IDE 会注入 `SERVER__PORT`，
> 会被 Spring 宽松绑定成 `server.port`）。命令行参数优先级最高，可用
> `java -jar ... --server.port=8080` 强制覆盖。

启动成功后：

| 地址 | 说明 |
|---|---|
| http://localhost:8080/actuator/health | 健康检查（backend1） |
| http://localhost:8081/actuator/health | 健康检查（backend2） |
| http://localhost:8080/swagger-ui/index.html | Swagger UI（prod 已关闭） |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |
| http://localhost:8080/actuator/prometheus | Prometheus 指标 |

> **多实例关键点**：两个容器跑的是**同一个镜像** `peakflow-backend:latest`，只靠环境变量
> `SNOWFLAKE_WORKER_ID`（1 与 2）区分雪花 ID 的机器位；定时任务用 Redisson 分布式锁（`lock:task:*`）
> 保证同一轮只有一个实例真正执行，另一个实例会打日志 `【任务名】本轮已由其他实例执行，跳过`。

### 6.5 启动前端

```bash
cd frontend
npm ci               # 严格按 package-lock.json 安装，比 npm install 更可复现
npm run dev          # http://localhost:3000

# 容器方式（Nginx 托管 + 反代后端，推荐）
cd ..
docker compose up -d frontend
```

> 容器方式下前端由 Nginx 托管在 **3001**，且 Nginx 已把 `/api/` 反代到 `upstream peakflow_backend`
> （`backend1:8080` + `backend2:8080`，`least_conn` 策略），因此容器模式下前端与后端同源，
> 不存在跨域问题，也不需要单独配 Vite 代理。

| 入口 | 本机 dev（3000） | 容器（3001） |
|---|---|---|
| 商城端 | http://localhost:3000/ | http://localhost:3001/ |
| 管理后台 | http://localhost:3000/admin/login | http://localhost:3001/admin/login |

---

## 七、环境变量与三套环境

配置采用 **「公共 + profile 覆盖」** 两层结构：`application.yml` 放公共项，
`application-{dev,test,prod}.yml` 只写差异化部分。

| 维度 | dev | test | prod |
|---|---|---|---|
| MySQL 库 | `peakflow_dev` | `peakflow_test` | `peakflow_prod` |
| Redis 逻辑库 | db0 | db1 | db2 |
| 日志目录 | `./logs/dev` | `./logs/test` | `./logs/prod` |
| 业务包日志级别 | DEBUG | INFO | INFO |
| Swagger / Knife4j | 开启 | 开启 | **关闭** |
| CORS 允许来源 | `localhost:3000` | 同左 | **默认空**，必须显式配置 |
| Actuator 暴露 | health/info/prometheus | 同左 | **仅 health**，不暴露细节 |
| JDBC `useSSL` | 不拼接 | 不拼接 | `DB_USE_SSL`（默认 true） |

**核心安全约定**：所有凭证类配置在 yml 中一律写成 `${VAR:}`（**无兜底默认值**），
未注入就直接启动失败 —— 宁可不启动，也不要带着空密钥跑起来。

完整变量清单（32 项，按域分组、含示例值与必填性标注）见 [`.env.example`](.env.example)。

---

## 八、演示账号

> 仅在加载了 `deploy/mysql/init/04-demo-account.sql` 的开发/演示环境可用。

| 账号 | 密码 | 角色 | 权限范围 |
|---|---|---|---|
| `pfadmin` | `Admin123456` | 超级管理员 | 全部 11 个权限点 |
| `sysadmin` | `Admin123456` | 超级管理员 | 全部 11 个权限点 |
| `productops` | `Ops123456` | 商品运营 | 商品/分类/秒杀/上传 + 看板 |
| `ordersvc` | `Service123456` | 订单客服 | 订单查询/发货 + 退款查询/审核 + 看板 |
| `malluser` | `User123456` | 普通用户 | 仅自己的订单/地址/购物车 |

**商城端登录**用 `malluser` 或 `pfadmin`；**管理后台**（`/admin/login`）需用带管理权限的账号（如 `pfadmin`）。

### RBAC 模型

```
user ──< user_role >── role ──< role_permission >── permission
```

- **4 个角色**：`admin`（11 权限）、`product_ops`（5）、`order_service`（5）、`user`（0）
- **11 个权限点**：`user:manage`、`stats:view`、`product:manage`、`category:manage`、`seckill:manage`、
  `order:read`、`order:ship`、`refund:read`、`refund:audit`、`refund:close`、`file:upload`
- **21 条角色-权限关联**

生产环境创建管理员的方式（**密码全程不落仓库**）：注册接口 → `deploy/mysql/prod/grant-admin-role.sql` 授权。
详见 [`deploy/mysql/README.md`](deploy/mysql/README.md)。

---

## 九、测试

### 9.1 后端接口测试（Postman / Newman）

`backend/postman/` 是**接口测试的唯一权威规范**：13 个文件夹 / 123 个请求 / 768 断言（当前 **0 失败**）。
上传类用例不要求 OSS 凭证——未配置时会自动降级写到本地 `./uploads` 并返回 `/uploads/xxx` 直链。

```bash
cd backend/postman

python seed_base_data.py         # 预置基础数据（10 分类 × 5 商品，含 10 个秒杀商品）
python setup_test_data.py        # 重置夹具（管理员密码 + 秒杀库存）
newman run PeakFlow-API.postman_collection.json -e env-test.postman_environment.json
python verify_db_redis.py        # 落库验证：DB 与 Redis 的真实副作用
```

测试按**业务链路**组织（而非「一个接口一个请求」），ID 全部靠脚本写入环境变量传递，零手工复制。
文件清单、分组设计、断言标准、变量传递与 **7 个已修复缺陷的复盘** 见 [`backend/postman/README.md`](backend/postman/README.md)。

### 9.2 前端测试（Vitest）

```bash
cd frontend
npm test                  # 单元测试（MSW 拦截 HTTP，无需后端）
npm run test:integration  # 集成测试（真联调：需要后端在跑）
npm run test:regression   # 回归测试（真联调，跑 test/regression）
npm run test:ui           # 可视化界面
```

> ⚠️ `test:integration` 与 `test:regression` 用的是 `vitest.integration.config.js`，**不装 MSW**，
> 属真联调测试：必须先按 6.1/6.4 把中间件与后端跑起来，否则会大面积 `ECONNREFUSED 127.0.0.1:8080`。
> 只有 `npm test`（`vitest.config.js`）用 MSW 拦截 HTTP，可脱离后端独立运行。

---

## 十、关键设计说明

### 10.1 秒杀：四层防线 + 异步削峰

```
① 购买权门闩（Redis）      同一用户同一商品只允许一单
② Redis 预扣库存（Lua）    单线程原子扣减，杜绝并发超卖
③ RocketMQ 异步落库        请求立即返回 requestId，前端轮询结果
④ DB 唯一索引兜底           seckill_slot 生成列 + uk_seckill_user_product
                           —— 不依赖 Redis（Key 会重启/过期/被清空）
```

**MQ 故障降级**：当 MQ 发送失败时降级为同步落库，且**刻意不回滚 Redis 预扣**——
因为降级同步落库会照常扣减 DB 库存，两边各扣 1 才一致；若回滚 Redis 就会造成
「Redis 还回去、DB 照扣」的库存漂移。原则是：**Redis 预扣是「占名额」，只有名额最终没被用掉时才回滚。**

### 10.2 库存预热与自愈

应用启动时**不会**自动把秒杀库存写进 Redis。首次下单若发现库存 Key 缺失（Lua 返回 `-2`），
会从 DB 重建后重试一次，实现自愈；也可由管理员调用 `GET /seckill/preheat/all` 全量预热。

### 10.3 订单与退款状态机

**订单状态**（`OrderStatus`）：`0 待付款 → 1 已付款 → 2 已发货 → 3 已完成`，
分支 `4 已取消`、`5 已关闭`（超时）、`6 已退款`。在途状态 SQL 常量 `0,1,2`。

**退款状态**（`RefundStatus`）：`0 待审核 / 1 退款中 / 2 退款成功 / 3 退款失败 / 4 已驳回 / 5 用户已撤销 / 6 已作废 / 7 待用户退货`。
中间态用 `OPEN_STATUS_SQL = "0,1,3,7"` 定义「未完结」，用 `CANCELLABLE_STATUS_SQL = "0,7"` 控制可撤销范围。

所有状态流转都走**乐观锁 CAS**（`UPDATE ... WHERE status = ?`）或 SQL 层条件约束，而非「先查后改」。

### 10.4 退款中态超时收敛

退款单最怕永久卡在「退款中」。项目用配置化的四条规则兜底（见 `refund.*`）：

| 配置 | 作用 |
|---|---|
| `retry-delay-minutes: 30` | 进入「退款中」后至少等 30 分钟才允许重试（防手抖 + 给自动重试限频） |
| `auto-retry-max-minutes: 360` | 超过 6 小时不再自动重试，只告警，转人工 |
| `applying-timeout-days: 7` | 「待审核」超 7 天自动驳回，释放「一订单一未完结退款单」名额 |
| `wait-return-timeout-days: 30` | 「待用户退货」超 30 天自动作废 |

### 10.5 下单风控：按「状态配额」而非「创建频率」

限流「下单频率」可以被「等一会儿再来」绕过；限制**同时持有的待付款订单数**则绕不过去。

| 配置 | 默认 | 作用 |
|---|---|---|
| `max-unpaid-orders` | 5 | 单用户同时可持有的待付款订单上限，支付/取消/超时后自动归还 |
| `abandon-window-days` | 7 | 统计窗口 |
| `abandon-per-penalty` | 5 | 窗口内每 5 次「超时未支付」配额收紧 1 笔（主动取消不计） |
| `max-quota-penalty` | 4 | 最多收紧 4 笔 |
| `min-unpaid-quota` | 1 | 配额下限，真正的封禁走封号流程 |

### 10.6 资金闭环的三道防线

| 防线 | 手段 |
|---|---|
| 真实性 | 回调**验签**（`MOCK_PAY_SECRET`）+ 时间戳防重放 |
| 正确性 | **金额比对**：回调金额必须与本地支付单一致，防改价攻击 |
| 幂等性 | 状态机 **CAS**：重复回调不产生副作用；重复预支付复用同一 `outTradeNo` |

---

## 十一、文档索引

| 文档 | 内容 |
|---|---|
| [`docs/API.md`](docs/API.md) | **接口文档**：通用约定、业务码表、状态机枚举、71 个端点总览、核心链路请求响应示例、权限对照 |
| [`backend/postman/README.md`](backend/postman/README.md) | 接口测试权威规范：分组设计、断言标准、变量传递、7 个已修复缺陷复盘 |
| [`deploy/mysql/README.md`](deploy/mysql/README.md) | 数据库初始化：五层文件切分、演示/生产两场景、生产账号授权三步法 |
| [`.env.example`](.env.example) | 环境变量模板：32 项变量按域分组，含必填性与生产注意事项 |

---

## 十二、已知事项

以下为走查后**已识别、尚未处置**的事项，如实记录：

| 事项 | 说明 |
|---|---|
| 演示密码明文散落 | `04-demo-account.sql`、`deploy/mysql/README.md`、`postman/README.md` 等多处含演示账号明文密码（仅演示用，生产不加载该 SQL） |
| `seed_base_data.py` 硬编码密码 | 本地开发脚本内含本地库密码常量，建议改为读环境变量 |
| 多实例下的本地降级上传 | 未配 OSS 时上传写各实例本地盘，A 写的文件 B 读不到。生产多实例必须改 OSS 或共享卷 |
| 雪花 ID 生成器暂无调用点 | 业务主键走 `id-type: auto` 自增，`SnowflakeIdGenerator` 已具备多实例分片能力但当前无人调用 |

> 项目**已包含**：`docker-compose.yml`、`backend/Dockerfile`、`frontend/Dockerfile`、
> `frontend/nginx/default.conf`、`deploy/nginx/default.conf`（宿主机 Nginx 方案）、
> `deploy/rocketmq/broker.conf`、`deploy/scripts/pull-images.sh`。
> `.gitignore` 与 `.gitattributes` 已就位。
>
> 已处置：早期版本遗留的 `/agent/session/**` 匿名放行与一组从未被代码读取的
> `agent.*` 配置，已连同 Agent 会话模块、Postman 用例与 `session_summary` 表一并移除。

---

## License

本项目仅用于学习与技术演示。内置的商品图片来自 Unsplash 公开可商用素材；
演示账号与 MockPay 密钥均为**示例值，不具备任何真实安全性**，请勿用于生产环境。
