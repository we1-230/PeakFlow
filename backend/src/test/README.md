# 单元测试规范说明（专项训练用）

> 本目录下测试分两大类：**单元测试**（`service/impl/`、`consumer/`，**131** 个用例）与
> **集成测试**（`integration/`，**28** 个用例）。两者遵循同一套 javadoc / 断言规范。
> **目的**：事后能快速看懂每个用例"测什么、为什么测、断言什么"，不需要重读业务代码。

---

## 零、先分清两类测试（**这是最重要的一节**）

| | 单元测试 | 集成测试 |
|---|---|---|
| **位置** | `service/impl/`、`consumer/` | `integration/` |
| **依赖** | **全 mock**（Mockito） | **真实 MySQL / Redis / RocketMQ** |
| **覆盖什么** | **逻辑分支**（幂等、状态机、金额校验…） | **真实环境特有的行为**：SQL 执行、事务、唯一约束、Redis 计数、MQ 链路 |
| **跑一次** | 0.9 秒 | 数十秒 |
| **要起环境吗** | 不要 | 要 |
| **用例数** | **131** | **28** |

### 选型原则：**工具要匹配问题**

| 问题 | 该用什么 | 为什么 |
|---|---|---|
| 状态机分支对不对 | 单元测试 | 逻辑确定，mock 掉依赖就能验 |
| **CAS SQL 真的拦住重复入账吗** | **集成测试** | mock 掉 DB 就等于把要验的东西 mock 掉了 |
| **唯一索引真的拦得住一人多单吗** | **集成测试** | 约束在 DDL 里，不在 Java 里 |
| **`@Transactional` 真的回滚吗** | **集成测试** | 事务要真实连接才存在 |
| **登录失败计数 / 账号锁定** | **集成测试** | 依赖真实 Redis 的原子自增与 TTL |
| **秒杀端到端（门闩→预扣→MQ→落库）** | **集成测试** | 秒杀入口依赖的 `acquirePurchaseGate` / `deductStock` 是 **private 方法**、预扣是 **Lua 脚本**、落库在 **MQ 消费者里（异步）**，Mockito 都到不了 |
| 并发下会不会超卖 | **压测**（JMeter） | 单元测试是串行的，压不出并发问题 |
| Redis/MQ 挂掉会怎样 | **故障演练** | 单测里依赖不会挂 |

> 一句话：**"逻辑用单测、真实行为用集成测试、并发用压测、容错用演练"** —— 每类问题用对的手段。

---

## 一、文件与命名

| 项 | 规范 | 例 |
|---|---|---|
| **单测位置** | `src/test/java/com/zhou/peakflow/service/impl/`（或 `consumer/`） | 与被测类同包，便于访问包级私有成员 |
| **集成测试位置** | `src/test/java/com/zhou/peakflow/integration/<域>/` | 继承 `integration/support/IntegrationTestBase` |
| **文件名** | `<被测类名><被测方法/域>Test` | `PayServiceImplNotifyTest` = 测 `PayServiceImpl#handleNotify` |
| **一个类测什么** | **一个方法**（或一个域的一条链路） | 不把多个方法的用例混在一个类里 |
| **用例方法名** | `<被测方法>_<场景>_should<预期>` | `handleNotify_amountTamperedDown_shouldReject` |
| **用例分组** | 用 `// 一、xxx` 这样的分组注释按"判定链顺序"划分 | 与生产代码的流程顺序一致 |

---

## 二、javadoc 规范（每个 `@Test` 前必须有）

```java
    /**
     * <p><b>为什么测它：</b>描述这条用例守护的风险 —— 不测会怎样（资损/超卖/状态不一致…）</p>
     * <p><b>关键断言：</b>列出本用例的核心断言点（含 never 路径断言）</p>
     */
    @Test
    @DisplayName("[分类] 场景 → 预期")
    void xxx_yyy_shouldZzz() { ... }
```

**`@DisplayName` 的分类前缀统一为 5 类**：

| 前缀 | 含义 |
|---|---|
| `[业务正确]` | 正常路径（happy path） |
| `[幂等]` | 重复请求不产生额外副作用 |
| `[攻击·xxx]` | 安全边界（改价 / 伪造签名 / 越权） |
| `[非法状态]` `[非法输入]` `[边界]` | 非法入参、非法状态、边界值 |
| `[并发]` `[容错]` | CAS 竞态、补偿动作失败 |

---

## 三、断言规范（防止"假阳性"）

### 3.1 每个用例必须至少有一个断言

要么 `assertXxx(...)`，要么 `verify(...)`（验证副作用）。**不允许只调用不断言。**

### 3.2 三类用例的断言重点

| 用例类型 | 断言重点 |
|---|---|
| **正常路径** | ① `assertDoesNotThrow(...)` 显式声明"不该抛异常"<br>② **断言副作用链**（用 `ArgumentCaptor` 断言入库对象每个字段） |
| **拒绝类** | ① 断言异常**码**与**消息关键内容**<br>② **路径断言**（证明失败发生在哪一步，见 3.3） |
| **并发/容错** | 断言"被抢先"与"补偿失败"两种情况的不同结果 |

### 3.3 路径断言（本套测试最重要的规范）

**问题的根源**：一个方法有多道校验，**多个不同原因会返回同一个错误码/同一个返回值**。
只断言返回值时，**前一道校验失败也能让测试变绿** → 假阳性。

**解决方案**：额外断言"**代码走到了哪一步**"。

```java
// 例 1：证明"失败发生在第 2 步而不是第 1 步"
assertEquals("failure", result);
verify(payRecordMapper).selectByOutTradeNo(...);   // 查到单 ⇒ 说明验签已通过

// 例 2：证明"根本没走到写操作"
assertEquals(3033, ex.getCode());
verify(refundRecordMapper, never()).insertRefundIfOrderRefundable(...);

// 例 3：封装成辅助方法，可读性更好
private void assertSignGatePassed() {
    verify(payRecordMapper, atLeastOnce()).selectByOutTradeNo(OUT_TRADE_NO);
}
```

**验证路径断言真的有效的办法——证伪实验**（做过一次，结论：能抓住）：
> 故意把签名密钥改成错的，跑测试 → **14 个用例失败**（7 个成功类被直接抓住 + 7 个靠路径断言抓住）。
> **如果没有路径断言，那 7 个用例会全部假阳性通过。**

---

## 四、Mock 规范

| 规范 | 说明 |
|---|---|
| **只 mock 依赖，不 mock 被测对象** | `@InjectMocks` 注入真实 Service |
| **私有方法不 mock**（Java 做不到），用"**它读了什么配置**"间接观测 | 例：`requestThirdPartyRefund` 第一步读 `getRefundUrl()` ⇒ 用"该配置是否被读取"当路径断言 |
| **签名类工具不 mock** | `PaySignUtil` 是纯函数，直接调它算真实签名（既验证了验签逻辑，又不会手写错） |
| **脚手架 stub 可用 `lenient()`，用例自身 stub 必须严格** | 深链路方法里不同用例走到不同深度，共用脚手架必然有一部分用不到 |
| **"不 stub"有时比"stub"更有说服力** | 例：已发货订单在 `autoApprove` 第二个判断就 return false，**不 stub 金额阈值恰好证明"拦截发生在读阈值之前"** |

---

## 五、造数规范

| 规范 | 说明 |
|---|---|
| **helper 别写死"业务入参"** | 我踩过：`doApply` 的 helper 写死了 reason，导致"空原因"用例测不到 |
| **越权用例必须显式改归属字段** | 我踩过：`orderOf()` 写死 `userId=当前用户`，导致"别人的订单"用例失效（期望 3007 却得到 3038） |
| **注意 NOT NULL / 生成列** | `order.address_id` 非空；`order.seckill_slot`、`refund_record.open_order_id` 是**生成列，不能 INSERT** |
| **注意业务前置条件** | 我踩过：造"登录失败计数=4"却没传验证码 —— 但 `failCount>=2` 就**强制验证码**，用例被挡在认证之前 |

---

## 六、两个 MyBatis-Plus / Mockito 的坑（必看）

### 1. `this.getById()` 会 NPE
```
NullPointerException: IService.getBaseMapper() is null
```
**原因**：`this.getById()` 读的是 **`ServiceImpl.baseMapper` 字段**，而 `@InjectMocks` 注入的是**构造器参数**。
**修法**（订单域两个测试类里已有）：
```java
@BeforeEach
void injectMybatisPlusBaseMapper() {
    ReflectionTestUtils.setField(orderService, "baseMapper", orderMapper);
}
```

### 2. `void` 方法不能用 `when()`
`ValueOperations.set(k, v, timeout, unit)` 返回 void ⇒ 必须用 `doThrow(...).when(mock).voidMethod(...)`

---

## 七、怎么跑

### 7.1 单元测试（131 个用例，**不需要任何中间件**）

```bash
JAVA_HOME="D:/jdk17/jdk-17.0.20+8" \
  /e/allJAVA/maven392/apache-maven-3.9.12/bin/mvn -o test \
  -Dtest='Pay*Test,Refund*Test,Order*Test,User*Test,SeckillConsumerTest'

# 单个类
... mvn -o test -Dtest=PayServiceImplNotifyTest
```

**当前状态：131 个用例，0.9 秒跑完，无需 MySQL / Redis / RocketMQ。**
（这正是"单元测试"相对"集成测试"的优势：快、隔离、随时可跑）

### 7.2 集成测试（28 个用例，**必须先起环境**）

#### 首次运行必做：同步 RBAC 基础数据（否则注册全挂）

**症状**：跑集成测试时，所有涉及"注册"的用例都返回
`{"code":5001,"message":"操作失败，该数据已存在，请勿重复操作"}`，且 `user` 表里查不到任何数据。

**根因**：`peakflow_test` 库的 **`role` / `permission` / `role_permission` 三张表是空的**。
注册流程里 `userMapper.userRole(username)` 会往 `user_role` 插数据，
而 `user_role` 有外键 `fk_user_role_role_id → role(id)` ⇒ **外键失败 → 整个事务回滚 → 用户没插进去**。
（报错文案"数据已存在"是全局异常处理器的兜底文案，**有误导性**。）

**修复**（从主库同步这三张表的数据即可）：

```bash
MY="/c/Program Files/MySQL/MySQL Server 8.0/bin"
"$MY/mysqldump.exe" -h 127.0.0.1 -P 3306 -u root -p1234 --no-create-info \
    --skip-add-locks --skip-comments --complete-insert peakflow role permission role_permission > /tmp/rbac.sql
"$MY/mysql.exe" -h 127.0.0.1 -P 3306 -u root -p1234 peakflow_test < /tmp/rbac.sql
```

**验证**：`SELECT COUNT(*) FROM peakflow_test.role;` 应为 **4**（普通用户 / 超级管理员 / 商品运营 / 订单客服）。

> **这是"测试库数据不完整"的典型问题** —— 表结构对齐了不代表数据对齐。
> 以后只要动过 `sql/` 目录里的基础数据脚本，都要同步到 test 库。

#### 运行命令

```bash
# ① 启动中间件（Docker Desktop 先启动）
docker start peakflow-redis peakflow-rmq-namesrv peakflow-rmq-broker

# ② 确认三个端口都在监听（3306 / 6380 / 9876）

# ③ 注入敏感配置（yml 已按安全规范去掉兜底默认值，不注入会连不上库 / 签名失败）
#    单元测试不需要；集成测试必须要，因为 test profile 的库凭证与密钥都改为环境变量注入
export DB_USERNAME=root DB_PASSWORD=<密码>     # 数据库凭证（application-test.yml）
export JWT_SECRET=<32位以上随机串>              # JWT 签名密钥（否则签发/校验 token 失败）
export MOCK_PAY_SECRET=<MockPay 验签密钥>       # MockPay 签名密钥

# ④ 跑集成测试
JAVA_HOME="D:/jdk17/jdk-17.0.20+8" \
  /e/allJAVA/maven392/apache-maven-3.9.12/bin/mvn -o test -Dtest='*IntegrationTest'
```

| 集成测试类 | 用例 | 覆盖 | 必需环境 |
|---|---|---|---|
| `SeckillFlowIntegrationTest` | 5 | 秒杀端到端（门闩→预扣→MQ→落库→轮询）+ **一人一单（断言业务目标而非拦在哪一层）** | MySQL + **Redis** + **MQ** |
| `ConstraintAndTxIntegrationTest` | 5 | `uk_seckill_user_product` / `uk_open_order` 真实拦重复 + **生成列 NULL 免约束语义** + 乐观锁 CAS | MySQL + Redis + MQ |
| `AuthFlowIntegrationTest` | 10 | 注册→登录→鉴权完整链路 + **BCrypt 密文验证** + **真实 Redis 失败计数/账号锁定** + **验证码用后即删** | MySQL + **Redis** + MQ |
| `PaymentRefundFlowIntegrationTest` | 8 | **支付回调真实 CAS 入账** + 幂等（update_time 不变作证）+ 改价/伪造签名被拒 + **退款回调三表跨事务一致** + **走接口验证 uk_open_order** | MySQL + Redis + **MQ** |
| `MockPayIdempotencyIntegrationTest` | 4 | **MockPay 台账落在共享 Redis（多实例一致）**：重复收款复用原交易号 / 重复退款单幂等重发 / 同一交易二次退款被拒并释放占位 | MySQL + **Redis** |

> **共 32 个集成用例**。**未启动环境时会失败** —— 不是代码问题，是缺少依赖。
> 用 `-Dtest='!*IntegrationTest'` 可以只跑单测。

#### 跑集成测试挖出的 5 个真实问题（都已在测试里修正）

| # | 问题 | 性质 |
|---|---|---|
| 1 | **`peakflow_test` 缺 RBAC 基础数据** | 环境问题（测试库数据不完整） |
| 2 | **基类常量过时**：`KEY_LOGIN_FAIL = "login:fail:"`，而生产是 `LOGIN_FAIL + account` = `login:fail{account}`（无冒号） | 测试侧与生产口径不一致 |
| 3 | **项目行为与注释不符**：无 token 访问受保护接口返回「HTTP 200 + 业务码 401」，**不是 HTTP 403** | 断言要按真实行为写 |
| 4 | **`SeckillResultVo.STATUS_WAITING = 0`**（「排队中」）被我误当成终态 ⇒ 轮询第一次就返回 | 常量语义理解错 |
| 5 | **同一用户连续秒杀会被「购买权门闩」拦下**，走不到唯一索引 | 引出下面的测试设计原则 |

> **由第 5 点引出的重要原则**：
> **断言"业务目标"，而不是"拦在哪一层"。**
>
> "在哪一层被拦"是实现细节（购买权门闩 / DB 唯一索引都可能拦下它，顺序还会变），
> 而**业务目标是唯一的："一人一单"**。
> ⇒ 所以那个用例只断言两件事：**第二次必须失败** + **库里只能有一条订单**。

### 7.3 集成测试为什么只做 4 个类（"范围最小但准"）

**设计原则：只测单测覆盖不了的**，否则就是重复劳动。

| 没做集成测试的部分 | 为什么可以不做 |
|---|---|
| 登录/注册的逻辑分支 | 已被 `UserServiceImplLoginTest`（13 个用例）覆盖 |
| 支付/退款的幂等与攻击类 | 已被 6 个单测类覆盖（70+ 用例） |
| 秒杀入口的门闩/预扣**逻辑** | 逻辑本身在 `SeckillConsumerTest` 里覆盖了"消费者侧"那一半 |
| 剩余 CRUD / 只读接口 | 无业务逻辑，靠 DB 约束兜底（面试可讲"按风险分层"） |
| 第三方回调的**完整 HTTP 链路**（MockPay → 8080） | 需要"启动真实应用"，属端到端测试范畴；本类用手工构造的**合法签名报文**，重点验服务端的真实 SQL/事务 |

**⇒ 集成测试补的是四类"真实环境才有"的东西**：
**① 数据库约束 ② 事务一致性 ③ Redis 真实计数 ④ MQ 异步链路** ——
这正是**"工具匹配问题"**的落地。
