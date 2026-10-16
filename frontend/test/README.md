# 前端测试与回归说明

> 本文档说明前端三阶段测试体系：**单元测试（Mock 后端）→ 前后端联调（真实后端）→ 用户操作回归配置**。

## 一、目录结构

```
test/
├── setup.js                  # 单元测试全局装配：MSW 服务端 + happy-dom 补丁 + Element Plus 弹窗 spy
├── handlers.js               # MSW mock：按后端真实契约返回 Result 包装
├── fixtures.js               # 种子数据 + 伪造 JWT（含 admin:manage 权限）
├── helpers.js                # 挂载助手（router/pinia/ElementPlus）、等待器、location 替换
├── units.test.js             # 工具函数与状态层：订单常量、购物车 store、api 拦截器
├── core/                     # 核心链路（用户指定重点覆盖）
│   ├── seckill.test.js       秒杀页面级：列表 / 未登录拦截 / 选地址 / 下单
│   ├── seckill-polling.test.js 秒杀轮询专项：排队/成功/失败/超时/异常/卸载清理
│   ├── pay.test.js           支付收款：prepay → 跳转收银台
│   ├── refund.test.js        退款：用户申请/撤销 + 管理端审核/确认退货/重试/作废
│   ├── login.test.js         登录：协议勾选/表单校验/落 token/跳转 + auth store
│   └── avatar.test.js        头像上传与回显：类型/大小校验、URL 破缓存、el-avatar 渲染
├── views/                    # 页面覆盖
│   ├── mall.test.js          首页/列表/详情/购物车/结算/地址/订单
│   └── admin.test.js         仪表盘/用户/商品/分类/秒杀/订单/退款 + 秒杀报文契约锁定
├── integration/              # 第二阶段：前后端联调（不装 MSW）
│   ├── setup.js              把 axios 指向真实后端（fetch 适配器）
│   ├── api.test.js           登录/秒杀(轮询)/支付收款/退款售后 全流程
│   └── avatar.test.js        头像上传 + 图片回显（node 环境，multipart）
└── regression/               # 第三阶段：用户操作回归配置
    ├── user-flow.json        声明式配置：覆盖前端全部接口的完整操作序列
    ├── run-flow.js           通用执行器（占位替换 / 列表回填 id / multipart / MockPay 签名）
    └── regression.test.js    逐条执行配置 + 接口覆盖率自查
```

## 二、三条命令

| 命令 | 作用 | 依赖 |
|---|---|---|
| `npm test` | 单元测试（Mock 全部后端接口）**9 文件 / 90 用例** | 无 |
| `npm run test:integration` | 前后端联调（真实后端）**3 文件 / 213 用例** | 后端 8080 已启动 |
| `npm run test:regression` | 用户操作回归（真实后端）**186 用例** | 后端 8080 已启动 |

> `test:integration` 会同时跑 `test/integration` 与 `test/regression`。

### 回归用例的四个维度

`user-flow.json` 共 **185 个步骤**，按验证意图分四类（步骤 id 前缀即分类）：

| 前缀 | 维度 | 数量 | 说明 |
|---|---|---|---|
| 无前缀 / `*.success` | **正常路径** | 97 | 完整业务闭环：登录→商品→购物车→下单→支付收款→退款售后→秒杀→管理端 |
| `param.*` | **参数校验** | 34 | 必填缺失、格式非法（账号/手机号/URL/分类名/昵称长度）、范围越界（页码 0、每页 101、价格 0、库存 -1、数量 11） |
| `biz.*` | **业务规则** | 46 | 权限 403、未登录/伪造 token 401、订单状态机、退款状态机与守卫（3031/3033/3034/3040）、一人一单、封禁登录、分类有商品不可删、上架商品不可删、支付幂等 |
| `edge.*` | **边缘与清理** | 8 | 边界值成功用例（分类名 2 字下限、每页 100 上限、数量 10 上限、价格 0.01 下限）与数据清理 |

**72 个负向用例**，其中 **42 个断言了精确失败码**（不是只看"报错了"）。

联调 / 回归前先预置基础目录 + 重置夹具：

```bash
# ① 基础种子数据：清理脏数据 + 写入 10 分类 × 5 商品（每类 1 条秒杀商品）
#    后端跑 test profile（peakflow_test 库 + Redis db1）
python java后端/postman/seed_base_data.py --db peakflow_test --redis-db 1
#    后端跑 dev profile（peakflow 库 + Redis db0）
python java后端/postman/seed_base_data.py --db peakflow --redis-db 0

# ② 夹具（重置管理员密码 + 秒杀库存 + 基线商品）
python java后端/postman/setup_test_data.py --db peakflow --redis-db 0 --seckill-ids 5,10 --base-product-id 1
```

> ⚠️ **夹具库必须与后端当前 profile 一致**，否则回归会大面积失败：
> `user-flow.json` 里硬编码依赖「商品 id=1 可见」和「存在一个进行中的秒杀商品」；
> `seed_base_data.py` 已保证：**id=1 是一条上架的普通商品**，且每类都带一条**活动进行中**的秒杀商品
> （秒杀商品 id = 5/10/15/…/50）。`setup_test_data.py` 的默认值（秒杀商品 5,10；基线商品 1）与之对齐。

## 三、配置驱动：新增回归用例只需改 JSON

`test/regression/user-flow.json` 里一条步骤就是一次用户操作：

```json
{
  "id": "order.create",
  "desc": "下单",
  "actor": "user",
  "api": "Order.create",
  "args": [{ "addressId": "{{addressId}}", "items": [{ "productId": 1, "quantity": 3 }] }],
  "save": { "orderId": "id" },
  "expectTruthy": ["id", "orderNo"]
}
```

支持的字段：

| 字段 | 说明 |
|---|---|
| `actor` | `user` / `admin` / `anon`，决定用哪个 token |
| `api` | `src/api.js` 里的调用，如 `Order.create` |
| `args` | 参数数组，支持 `{{变量}}` 占位（变量来自前置步骤的 `save`） |
| `save` | 把响应字段存进上下文：`{"orderId": "id"}` |
| `saveFind` | 列表里按字段找一条再取值（新增接口不返回 id 时用） |
| `expectTruthy` / `expectEquals` | 断言语义 |
| `expectError` | 负向用例：期望这次调用失败 |
| `expectErrorCode` | 精确断言失败的业务码（如 1003 参数校验 / 403 越权 / 3031 状态不允许） |
| `transport` | `multipart`（文件上传）/ `mockpay`（第三方收款）/ `delay`（等待） |

## 四、已知坑与规避（都写在用例注释里了）

1. **JWT 无随机 jti（已修复）**：此前同一秒内为同一用户签发的 token 完全相同，登出/刷新后立刻重登会拿到刚进黑名单的同号 token → 401；回归里曾用 `transport: delay` 规避。现已在签发时加入随机 `jti`（`JwtUtil.generateToken` / `generateRefreshToken`），同秒重登不再撞号，**两个 delay 规避步骤已移除**。
2. **axios 在非浏览器环境会丢 Authorization 头**：happy-dom 的 XHR 实现不传自定义头，联调统一用 `http.defaults.adapter = 'fetch'`。
3. **multipart 不能在 node/happy-dom 下走 axios**：axios 只在标准浏览器环境才把 boundary 交给运行时。回归执行器改为手工拼 multipart 报文（与浏览器一致）。
4. **Element Plus 下拉组件在 happy-dom 下递归更新**：`el-pagination` / `el-select` / `el-date-picker` 已全局桩掉（属筛选控件，非业务逻辑）。
5. **下单有防重锁**（用户 + 商品清单 MD5），回归里每单用不同数量的商品清单；且单个商品数量上限为 10。
6. **后台角色的判定口径（已修）**：前端 `isAdmin` 曾按 `admin:manage` 判定，但**后端 permission 表里并不存在该权限码**（实际是 `user:manage` / `product:manage` / `order:read` … 共 11 个），
   导致**任何账号（含超级管理员）都进不了管理后台**。现按真实权限码判定，并在 `AdminLayout` 里按权限点收敛菜单（商品运营看不到订单/用户，订单客服看不到商品）。

## 五、与后端约定的对应关系

- 后端单元 + 集成测试：`java后端` 下 `mvn test`
- 后端接口回归：`java后端/postman` 下 newman 跑 `PeakFlow-API.postman_collection.json`（项目对外端点共 75 个，集合覆盖其中 72 个；未覆盖的 3 个为 `/mcp/**` MCP 网关端点，由 `McpAuthInterceptor` 独立鉴权、不在本集合范围）
- 前端回归：`npm run test:regression`（覆盖前端 `src/api.js` 暴露的全部接口，含覆盖率自查用例）
