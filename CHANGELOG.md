# 更新日志（CHANGELOG）

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
日期为北京时间。

## [Unreleased]

## [1.2.0] - 2026-10-17

### 修复

- **秒杀商品详情页误报「已售罄」**：后端 `stock`（普通库存，秒杀商品恒为 0）与
  `seckillStock`（真实可抢量）是两个独立字段，详情页展示/售罄判断一直读 `stock`，
  只有抢购校验读 `seckillStock`，导致「页面显示 0 件、点抢购却成功」。
  现统一按 `isSeckill` 取正确口径，展示与校验同源。
  （`frontend/src/views/mall/ProductDetail.vue`）
- **登录日志「成功」显示成「失败」**：前端按 `row.status === 1` 判断，
  但 `login_log` 表与 `UserLoginLog` 实体**没有 `status` 字段**（只有
  id/user_id/username/ip/login_time/remark），`undefined === 1` 恒 false。
  状态列改按 `remark` 渲染，并移除永远为空的「登录地点 / 设备」两列。
  （`frontend/src/views/mall/UserCenter.vue`）
- **取消秒杀订单未释放 Redis 购买权门闩**：DB 限购位在取消后已释放（允许重新参与），
  Redis 门闩（24h）只在失败路径释放 ⇒ 用户取消后再抢被拦 24 小时。
  `OrderServiceImpl#rollbackStock` 秒杀分支现同步释放门闩。
  （`SeckillOrderService#releasePurchaseGate`，接口契约**未变**，仅新增方法）
- **登录页协议勾选框勾不上**：协议链接曾放在 `el-checkbox` 的 label 内并用
  `.stop` 阻止冒泡，导致复选框关联勾选失效。现把链接移出 label。
- **支付确认失败（JSON 解析）**：MockPay 收银台把收款接口写死为根相对路径
  `/mock-pay/pay`，生产经 nginx `/api` 前缀暴露时打到根路径 → 405 HTML →
  `r.json()` 抛 `SyntaxError` → 前端显示「网络异常」。改为文档相对路径 `pay`。
- **部署冒烟误报**：启用 HTTPS 后 80 端口一律 301，`deploy.sh` 冒烟却打
  `http://127.0.0.1:80` 并期望 200 ⇒ 每次部署都误报失败。改为探 `https://127.0.0.1`。

### 新增

- **购物车角标**：加购/改数量/删除后实时刷新；数量 > 99 显示 `99+`；为 0 时不显示。
  （`stores/cart.js` 提供 `count` + `refresh()`）
- **登录页《用户协议》《隐私政策》弹窗**：新增 `src/constants/agreements.js` 存放协议全文，
  点击协议名弹出 `el-dialog`（风格与登录卡片一致），弹窗内可一键同意。
- **统一错误提示**：新增 `classifyHttpError`，把错误分为
  `network`（网络）/ `permission`（权限）/ `server`（接口）/ `format`（数据格式）四类，
  替换笼统的「网络错误」。

### 变更

- **移动端适配（≤768px 增量样式）**：新增 `styles/mobile.css`，覆盖商城首页、商品列表、
  商品详情、购物车、结算、订单、个人中心、登录页、后台登录与后台主要页面；
  后台窄屏自动折叠侧栏。桌面端布局/字号/间距**完全不变**（所有覆盖都在媒体查询内，
  对 scoped 样式用 `!important` 穿透）。
- 秒杀商品详情数量框固定为限购 1 件并明示原因（后端唯一索引限购、下单接口固定 1 件）。
- 清理 `taobao.css` 死代码（`.cat-nav-four` / `.seckill-countdown` / `.btn-outline`）。

### 测试

- 前端 `vitest`：15 个文件 / **122 用例**全绿（新增 22 个：库存口径 5、登录日志 2、
  协议弹窗 5、错误分类 7、角标 99+ 1、收银台 6 …）。
- 后端 `mvn test`（单元层）：**153 用例**全绿（新增取消订单释放门闩 1 例）。
- 生产端到端：支付全链路 9/9、权限矩阵与购物车/订单/秒杀并发 46/46。
- **修正测试桩造假**：`test/handlers.js` 的 `loginLog` 曾臆造 `status/location/device`，
  与后端真实契约不符 —— 这正是登录日志 bug 逃过测试的原因，已改为真实字段。

### 文档

- `docs/API.md`：补充 `stock` vs `seckillStock` 语义对照、`/user/loginLog` 真实响应字段。
- `docs/上线部署全流程.md`：坑清单扩至 21 条（新增 19~21）。

## [1.1.0] - 2026-10-16

### 新增

- 生产部署脚本体系：`deploy.sh`（一键构建/启动/等健康/重启 nginx/冒烟）、
  `server-init.sh`、`gen-prod-env.sh`、`issue-cert.sh`、`backup.sh`。
- 生产 nginx 模板与片段：HTTP/HTTPS 共用 locations、安全响应头片段、ACME 挑战。

### 修复

- 生产 nginx 未开 gzip（首屏 5~8s → 1.25MB→404KB）。
- HSTS 被 location 级 `add_header` 覆盖而静默丢失。

## [1.0.0] - 2026-10-15

### 新增

- 秒杀商城首个可运行版本：Spring Boot 3 + MyBatis-Plus + MySQL 8 + Redis +
  RocketMQ + Vue 3/Vite；商品/购物车/订单/退款/MockPay 模拟支付/秒杀、
  RBAC 四级权限模型、Docker Compose 一键起栈、Postman/Newman 接口回归。
