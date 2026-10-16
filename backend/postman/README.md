# PeakFlow 商城 Postman 接口测试规范

> 本文档是 PeakFlow 秒杀商城接口测试的**唯一权威规范**，覆盖测试套件的组织、断言标准、变量传递、
> 落库验证与已知缺陷。替代旧的 `openapi_to_postman.py` 自动生成方案（那版断言引用了不存在的 `success` 字段、
> 且把混组场景推给集成测试，不符合「Postman 全面混合测试」的要求）。

---

## 一、文件清单

| 文件 | 作用 |
|---|---|
| `PeakFlow-API.postman_collection.json` | 测试集合（13 个文件夹 / 123 个请求），由 `build_collection.py` 生成 |
| `env-test.postman_environment.json` | 测试环境变量（baseUrl / token / 各类业务 ID） |
| `build_collection.py` | 集合生成器（改接口后改这里再重跑，别手改 JSON） |
| `seed_base_data.py` | **基础种子数据预置**：清理验收脏数据（业务表 + 测试账号 + Redis 脏键）并写入 **10 个分类 × 5 条商品**（每类 1 条秒杀商品，共 40 普通 + 10 秒杀），商品名称/描述/详情/图片齐全，图片为互联网公开可商用真实实拍图（与品类匹配）。参数化 `--db` / `--redis-db`，默认 dev（peakflow + db0） |
| `setup_test_data.py` | 测试夹具：重置管理员密码 + 秒杀库存 + 基线商品（DB 与 Redis 双重置）。支持 `--db` / `--redis-db` / `--seckill-ids` / `--base-product-id`，默认值对应种子目录（秒杀商品 5,10；基线商品 1）；dev 环境示例：`--db peakflow --redis-db 0 --seckill-ids 5,10 --base-product-id 1` |
| `verify_db_redis.py` | Newman 跑完后做 DB/Redis 落库验证（"全面检查"） |
| `newman-report.json` | Newman 每次运行的原始报告（失败详情看这里） |
| `newman-report-regression.json` | 本轮全量回归报告（123 请求 / 768 断言；`/user/avatar`、`/upload/image` 两条上传用例需配置 OSS 凭证） |
| `fixtures/test-avatar.png` | multipart 上传用例（`/user/avatar`、`/upload/image`）用的测试图片 |

---

## 二、快速开始（3 步）

### 第 0 步：环境准备（一次性）

| 组件 | 端口 | 启动方式 |
|---|---|---|
| MySQL | 3306 | 系统服务 MySQL80（测试库 `peakflow_test`） |
| Redis | 6380 | `D:\tools\redis\redis-server.exe --port 6380`（测试环境 db1） |
| RocketMQ | 9876 / 10911 | 任选其一：Docker 容器 `plekflow-rmq-namesrv` + `plekflow-rmq-broker`；或本机 `D:\rocketmq\rocketmq-all-4.9.7-bin-release\bin\mqnamesrv.cmd` + `mqbroker.cmd -n 127.0.0.1:9876`（JDK17 需用本仓库已改好的 runserver/runbroker 脚本） |
| 应用 | 8080 | `java -jar target/PeakFlow-1.0-SNAPSHOT.jar --spring.profiles.active=test` |

```bash
# ①（推荐）先预置基础目录：清理脏数据 + 写入 10 分类 × 5 商品（含秒杀商品）
#    ⚠️ 脚本默认参数是 dev（peakflow + Redis db0）。本规范要求 App 以 test profile 启动，
#       所以必须显式指定测试库，否则种子会灌进 dev 库，秒杀用例全部因「商品不存在」失败。
python seed_base_data.py --db peakflow_test --redis-db 1
# ② 夹具（重置管理员密码 + 秒杀库存，保证可重复执行）
#    默认值已对应 test 环境，无需传参
python setup_test_data.py
```

### 第 1 步：导入 Postman

```
Postman → Import → 选 Files → 导入 PeakFlow-API.postman_collection.json
Postman → Import → 选 Files → 导入 env-test.postman_environment.json
右上角环境下拉框 → 选中【PeakFlow-test】
```

### 第 2 步：跑

```bash
# 方式一：Postman 手工（Runner → Run collection）
# 方式二：Newman 进 CI（推荐）
newman run PeakFlow-API.postman_collection.json -e env-test.postman_environment.json

# 跑完做落库验证（查 DB + Redis 真实副作用）
python verify_db_redis.py
```

---

## 三、分组与混合链路设计（不是"一个接口一个请求"）

按**业务链路**分 13 个文件夹，每个文件夹是一条可独立跑的端到端链路，文件夹之间靠**环境变量**传递 ID。
其中 00–09 是核心业务链路，10–11 是为**端点全覆盖**补齐的接口（管理端 CRUD、订单履约/售后、个人资料、上传）。

| 文件夹 | 链路 | 请求数 |
|---|---|---|
| 00-前置准备 | 注册 → **登录(存 token)** → 登录管理员(存 adminToken) → 造地址 | 5 |
| 01-认证安全链路 | 登录成功/密码错/账号不存在/空账号/重复注册/未登录401/伪造token/登出/登出后访问 | 10 |
| 02-商品浏览 | 分页/详情/详情不存在/分类/秒杀列表/秒杀详情 | 6 |
| 03-购物车 | 加购→列表→改数量→删除→清空 | 5 |
| 04-地址管理 | 新增→列表→设默认→查默认→参数错误 | 5 |
| 05-下单链路 | 下单成功/空列表/超数量/秒杀商品/越权地址→详情→列表→取消→二次取消→订单不存在 | 10 |
| 06-支付资金闭环（核心） | 下单→预支付(存outTradeNo)→幂等重复预支付→伪造签名拒绝→篡改金额→**MockPay收款(自动回调)**→查支付单=成功→查订单=已付款→重复回调幂等 | 9 |
| 07-退款资金闭环（核心） | 申请退款→重复申请被拒→详情(待审核)→**管理员审核(自动退款+回调)**→详情(退款成功)→订单(已退款)→伪造签名→篡改金额→退款列表 | 9 |
| 08-秒杀闭环（核心） | 预热→秒杀(存requestId)→轮询结果→一人一单重复被拒→参数校验 | 5 |
| 09-管理端（RBAC） | 用户列表/订单列表/退款列表/仪表盘 + 普通用户越权403 | 5 |
| 10-管理端 CRUD 全覆盖 | 分类 增/改/删 → 商品 增/查回填id/改/下架/上架/删 → 分类删 → 秒杀分类 → 秒杀商品 增/列表回填/改/删（活动期守卫） → 封号/解封/重置密码 + 越权403 | 22 |
| 11-订单履约/售后/个人资料/上传 | 下单→支付→发货→确认收货→售后退货退款（撤销/审核/确认收货/重试与作废状态守卫）→ 验证码/刷新token/改密/改昵称/头像与图片上传(multipart)/登录日志/地址改删 | 30 |
| 12-模拟第三方（MockPay） | 收银台页面（GET，校验返回真实 HTML 而非 forward 字面量）→ 第三方退款入口（合法签名 + 不存在的原交易 → 拒绝） | 2 |

> **覆盖度**：以 springdoc 导出的权威清单为准，项目**对外端点共 71 个**（可由 `OpenApiSpecExportTest` 输出到 `target/api-docs.json` 核对）。
> 本集合**全覆盖这 71 个**。
> 10/11/12 三组自带数据自清理（建了删、改了还原），可重复执行。
>
> 唯一例外：秒杀商品在**活动期内**接口不允许删除（业务规则），
> 所以每轮会在测试库留下 1 条秒杀商品 + 1 条秒杀专用分类（不是缺陷）。

**混合测试要点**：
- 鉴权依赖：登录前访问受保护接口 → 401；登录后 → 200（同一接口两种前置状态）
- 数据链依赖：下单的 orderId → 预支付的 outTradeNo → 退款的 refundId，靠脚本自动写变量，**全程零手工复制 ID**
- 状态机依赖：取消后二次取消被拒、支付后重复回调幂等、退款后重复申请被拒

---

## 四、断言标准（业务码 + 消息 + 格式统一）

### 4.1 统一响应结构（集合级 Test，所有请求自动跑）

项目约定 `Result = { code, message, data, timestamp }`，HTTP 恒为 200，错误用业务码区分。
集合级断言强制校验 `code/message/timestamp` 三字段存在、`code` 为数字 —— **格式不统一立刻被发现**。

### 4.2 四类场景覆盖

| 场景 | 断言 | 示例 |
|---|---|---|
| 业务正确 | `code=200` + `message` 非空 + `data` 关键字段 | 登录返回 token、下单返回 orderId |
| 边界值 | 精确业务码 | 数量超上限 → 3005、秒杀重复 → 3001 |
| 入参错误 | `code=1003`（参数校验）/ `1002`（类型错误） | 空账号、手机号格式错误 |
| 业务错误 | 精确业务码 + 消息关键字 | 越权 3007、退款重复 3033、订单不存在 3003 |

### 4.3 特殊响应（非 Result JSON）

| 接口 | 返回格式 | 断言 |
|---|---|---|
| `/pay/notify`、`/refund/notify` | 纯文本 `success` / `failure` | 文本精确比对 |
| `/mock-pay/pay`、`/mock-pay/refund` | `{ success, message }` | `success === true` |

---

## 五、变量传递（别手工复制 token/orderId）

| 变量 | 谁写入 | 谁读取 |
|---|---|---|
| `userToken` | 00 登录普通用户 | 集合级 Pre-request 自动加 `Authorization: Bearer {userToken}` |
| `adminToken` | 00 登录管理员 | 管理端请求（请求级 Pre-request 覆盖为 adminToken） |
| `orderId` / `orderNo` / `payAmount` | 下单 | 预支付、退款、查单 |
| `outTradeNo` | 预支付 | 支付查询、MockPay 收款、回调 |
| `refundId` / `refundNo` | 申请退款 | 退款详情、审核、回调 |
| `requestId` | 秒杀下单 | 秒杀结果轮询 |
| `smokeUser` / `smokeEmail` | 注册（时间戳生成唯一值） | 登录、重复注册 |

集合级 Pre-request 脚本：只要 `userToken` 存在，就给所有请求自动带 Authorization —— **不用给每个请求手填 header**。

---

## 六、DB / Redis 落库验证（Postman 查不了的，用脚本补上）

Postman 只能看接口响应，查不了真实副作用。`verify_db_redis.py` 在跑完后对照资金/秒杀链路检查：

| 检查项 | 预期 |
|---|---|
| 新用户密码 | BCrypt 哈希 |
| 资金闭环订单 | `status=6`（已退款） |
| 支付单 | `status=3`（已退款）+ `trade_no` 非空 |
| 退款单 | `status=2`（退款成功）+ `refund_trade_no` 非空 |
| 秒杀订单 | `order_type=1` 且已落库 |
| 秒杀库存 | DB `seckill_stock` 与 Redis `seckill:stock:{id}` **一致** |
| 购买权门闩 | `seckill:order:success:{userId}:{pid}` 存在（一人一单） |
| 登录缓存 | `login:user:{username}` 存在 |

---

## 七、⭐ 走查发现的缺陷（含修复状态）

### Bug 1（已修复）：支付/退款回调字段命名不一致

- **现象**：MockPay 用下划线发回调（`out_trade_no`），`PayNotifyDto` 用驼峰（`outTradeNo`），Jackson 不自动转下划线 → `outTradeNo=null` → 验签缺参数 → **资金闭环完全断掉**。
- **修复**：`PayNotifyDto`、`RefundNotifyDto` 加 `@JsonProperty("out_trade_no")` 等映射。

### Bug 2（已修复）：MockPay.stringValue 污染 timestamp

- **现象**：`stringValue` 对所有 Number 统一 `setScale(2)`，把 `timestamp` 这个 long 变成 `1792039653626.00`，与商户侧签名的 `1792039653626` 不一致 → **退款请求验签失败**。
- **修复**：只对 `Double/Float/BigDecimal` 做两位小数规整，整数直接 toString。

### Bug 3（已修复）：登出不生效

- **现象**：登出后旧 token 仍能访问（`login:user` 缓存未真正删除）。
- **根因**：① `UserServiceImpl` 有 4 处删除/写入登录缓存的 key 少冒号（`login:user + username` = `login:userxxx`，而登录写入与鉴权校验用的是 `login:user:xxx`），删/写全部落空；② `JwtUtil.addToBlacklist` 用带 `Bearer` 前缀的原始 token 做 key，而 `parseToken` 检查时用剥前缀后的裸 token，黑名单 key 对不上；③ `jwt.token-prefix` 配成 `Bearer`（无尾空格），`removeTokenPrefix` 剥完会留下前导空格。
- **修复**：`UserServiceImpl` 4 处补 `":"`；`removeTokenPrefix` 末尾加 `.trim()`；`addToBlacklist` 入口先归一化成裸 token 再拼 key。
- **现状**：用例「登出后旧 token 访问」已从「200 或 401 都算过」收紧为**必须 401**，现在通过。

### Bug 4（已修复）：JWT 无随机 jti，同秒签发的 token 完全相同

- **现象**：登出（或改密强制下线）后**同一秒内**重新登录，拿到的 token 与刚进黑名单的 token **字符串完全一致**，导致新 token 立即被判失效（401）。
- **根因**：`JwtUtil.generateToken` 的 claim 只有 `userId / username / role / tokenType`，`iat` 精确到秒，没有 `jti` 之类的随机因子 —— HS256 对相同 payload 必然产出相同签名字符串。
- **影响**：登出/改密后立刻重新登录的用户可能被误判为未登录；对"改密后强制重登"的交互尤其容易触发。
- **修复**：`generateToken` / `generateRefreshToken` 增加 `claims.put("jti", UUID.randomUUID().toString())`。
  黑名单仍以裸 token 记 key（token 已唯一，无需改成按 jti 记）。
- **现状**：已实测同秒两次登录拿到**不同** token；回归里为规避该竞态加的 `transport: delay` 步骤已移除。

### Bug 5（已修复）：MockPay 收银台页面返回 `forward:` 字面量

- **现象**：`GET /mock-pay/cashier` 返回的是一串字面量 `forward:/mock-pay-cashier.html`，收银台页面打不开
  （而 `mockpay.cashier-url` 正指向它，用户点"去支付"打开的就是这个坏链接）。
- **根因**：`MockPayController` 是 `@RestController`，方法返回值会被当作**响应体**原样输出，**不会**触发视图转发；
  且重定向会丢失 URL 上的 `out_trade_no`（页面 JS 靠 `location.search` 取值），不能改用 redirect。
- **修复**：改为内联读取并返回静态页面内容（`produces = text/html`），保留查询参数。
- **现状**：`12-模拟第三方（MockPay）` 文件夹的 H1 用例锁定"响应体必须是页面 HTML 且不含 `forward:`"。

### Bug 6（已修复）：`/auth/changePassword` 对外匿名开放

- **现象**：`SecurityConfig` 用 `.antMatchers("/auth/**").permitAll()` 放行了整个 `/auth`，
  且 `security.white-list` 也含 `/auth/changePassword`，导致"按用户名强制改密"可被**任何人匿名调用**。
- **修复**：① 从 `security.white-list` 移除；② 在 `SecurityConfig` 中把它单独声明为 `.authenticated()`（排在 `/auth/**` 之前）；
  ③ `AuthController` 增加越权校验 —— 仅允许**本人**（登录态用户名 == 目标用户名）或持有 `user:manage` 的管理员调用。
- **现状**：Newman `A5b` / 前端回归 `auth.changePassword.anon` 用例断言匿名调用返回 `401`。

### Bug 7（已修复）：`/user/password` 的参数校验未生效

- **现象**：`UserController.changePassword` 用了 `@RequestBody UpdateUserPasswordDto` 但**漏了 `@Valid`**，
  DTO 上的 `@NotBlank/@Pattern` 全部不生效（旧密码传 `null` 会走到 BCrypt 抛 IllegalArgumentException，而非返回参数错误码）。
- **根因**：该 DTO 与 `/auth/changePassword` 共用，`username` 字段是 `@NotBlank`；直接补 `@Valid` 会误伤自助改密接口。
- **修复**：拆出 `ChangePasswordDto`（只含 `oldPassword/newPassword` + 校验注解）供 `/user/password` 使用，
  `UpdateUserPasswordDto` 保留给按用户名的强制改密。

---

## 八、环境变量清单

| 变量 | 值 | 说明 |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | 后端地址 |
| `adminUser` / `adminPass` | `pfadmin` / `Admin123456` | 管理员账号（`setup_test_data.py` 重置） |
| `mockPaySecret` | `peakflow-mock-pay-secret-key-2026` | MockPay 签名密钥 |
| `seckillProductId` | `5` | 秒杀测试商品（`seed_base_data.py` 生成的秒杀商品，id=5/10/…/50） |
| `productId` | `1` | 普通测试商品（种子目录里的第 1 条商品，id=1） |

---

## 九、维护约定

1. **改接口后**：改 `build_collection.py` → 重跑 `python build_collection.py` 重新生成集合，**不要手改 JSON**。
2. **跑用例前**：先 `python setup_test_data.py` 重置夹具（否则多轮跑会残留 Redis 库存、秒杀商品售罄）。
3. **跑完必查库**：`python verify_db_redis.py`，别只看接口返回的 200，要确认 DB/Redis 真实落库。
4. **发现断言失败先看 `newman-report.json`** 里的响应体和失败断言，别急着改代码。
