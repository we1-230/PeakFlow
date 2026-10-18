# 更新日志（CHANGELOG）

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
日期为北京时间。

## [Unreleased]

### 修复（2026-10-18 线上性能专项）

- **上传的图片从外网根本打不开（功能缺陷，不只是慢）**：nginx 缺 `location /uploads/`，
  请求落到 SPA 回退规则返回 `index.html` ⇒ `<img>` 拿到 `text/html` 解码失败。
  实测同一文件：直连后端 `image/png 1330273 bytes`，经 nginx `text/html 875 bytes`。
  用户反馈的「头像加载非常缓慢」实际是「头像一直加载不出来」。
  新增 `location ^~ /uploads/` 反代到 backend + 30 天缓存 + 仅允许 GET。
- **上传图片原样落盘，单个头像 1.33MB**：只校验 ≤5MB 就原样写入，无缩放无重编码。
  在 60KB/s 的跨国链路上一个头像就要 20 秒。新增 `ImageCompressor`（纯 JDK ImageIO），
  按最长边 400px 缩放 + 重编码，实测省 93%（613KB → 45KB）；异常一律回落原图。
- **前端主 chunk 单文件 1.22MB**：`import * as ElementPlusIconsVue` 把 293 个图标
  全注册（实际只用 28 个），加上 element-plus 全量组件全进首屏。
  改为图标按需注册 + `manualChunks` 拆包 ⇒ 主 chunk 1222KB → 14KB，
  且 element-plus 可跨版本长期缓存。发版只需重下 5~14KB 业务代码。
- **商品图统一 `w=800` 且全部来自境外 unsplash**：列表卡片实际展示仅 200~260px。
  新增 `resolveImage()` 按场景取尺寸（thumb 400 / mini 200 / large 800 / banner 1000），
  q 降到 65 + `auto=format`（CDN 给 webp），非首屏加 `loading="lazy"`。
  只对 unsplash 生效，OSS/本地地址原样返回。
- **静态资源缓存不完整**：`/assets/` 补 `immutable`（省掉每资源 ≈330ms 的 304 往返）；
  `/uploads/` 补 30 天缓存（原先完全没有缓存头）。
- **首页轮播大图未走优化**：硬编码 `w=800&h=400&q=80`，是首屏视觉面积最大的一张，
  现接入 `resolveImage(IMG_SIZE.banner)`。

- **压缩代码从未被调用**：`ImageCompressor` 编译进 jar 了，但
  `OssUploadServiceImpl` 的接入改动在某次 `git reset --hard` 中丢失
  （该类是已跟踪文件，reset 连修改一起回滚；新建的类文件反而幸存）。
  形成「类在、单测全绿、线上不生效」。已重新接入，并补
  `OssUploadServiceImplTest`（5 用例）——从上传入口进、抓真正写给 OSS 的字节流断言体积。
- **图片 URL 多了一段 `/api`**：`docker-compose.yml` 里
  `APP_UPLOAD_PUBLIC_BASE_URL: ${PUBLIC_BASE_URL}/api`。`/api` 是接口前缀，
  图片不该带 ⇒ 请求落到 `location /api/`（限流 + no-cache），
  绕过了图片专用的 `/uploads/`（30 天缓存）。已去掉；
  历史 `/api/uploads/...` 由新增兼容 location 继续支持，缓存升级为 30 天。

- **头像上传走的是另一条链路，压缩从未生效**（2026-10-18）：
  图片压缩只加在了 `POST /upload/image`（后台商品图），而**用户头像走
  `POST /user/avatar` → `UserServiceImpl#changAvatar`**，那条路径仍是
  「原始 InputStream 直接上传」。线上实测头像文件 1.33MB / 1.6MB 原样落盘。
  已给 `changAvatar` 接入同一套压缩（实测 3.88MB → 51KB，省 98.7%）。
  **教训：同一能力有多个入口时必须逐个确认接入。**
- **头像上传上限 2MB → 5MB**（三处同步：Java 常量 / application.yml / 前端校验）。
  服务端本就压到 400px，而手机原图普遍 3~8MB —— 卡 2MB 会让大量真实用户
  在选图那一刻就被拒绝。拒绝提示也补了可行建议。

- **图片全部本地化（2026-10-18）**：商品图 100 条、分类图标 10 条、前端轮播 2 张
  原先**全部指向 `images.unsplash.com` 境外 CDN**（用户以为是存在自己服务器上，
  实际查库发现并非如此）。已全部下载到本机 `uploads/products/`，
  生成 400/800/1000 三档（分级质量 q70/q82/q78），数据库指向本机地址。
  实测：原图 28.8MB → 800 档 6.1MB；首屏图片 720KB → **558KB**；
  **二次访问 load 0.3s**（图片全命中 30 天缓存）。
  顺带修掉 15 条「favicon.ico 当商品图」的脏数据。
- **首屏图片优先级**：轮播 + 秒杀前 6 + 网格前 8 用 `fetchpriority="high"`，
  其余保持 lazy。起因是发现 Chrome 在慢网下会把 lazy 预加载阈值扩大到约 3000px，
  导致 32 张图仍然全部请求 —— lazy 属性"加了但不生效"。

### 新增

- `tools/localize_images.py`：图片本地化工具。支持 `--download`（下载并生成多档）、
  `--repack`（改质量后从 -800 重压 -400，不必重下）、`--banners`（轮播 1000×400 裁剪）、
  `--verify`（逐条校验库里 URL 可访问）。改库前会校验文件真实存在，缺文件即中止。

- `tools/fresh_device_check.py`：全新设备端到端体检（28 项 / 四角色）。
  每页检查 JS 错误、破图、关键组件、横向溢出、登录态、三档耗时。
- `UserServiceImplAvatarTest`（6 用例）：守住「头像压缩真的接进了上传链路」。

- `tools/compress_uploads.py`：存量上传图片压缩脚本。默认 dry-run，`--apply` 才写，
  原图备份到 `<dir>-orig/`。**两条铁律**：保持原文件名（含扩展名，否则数据库 URL 404）、
  保持原 uid/gid（否则后端 UID1001 读不了）。实测线上 6 个文件 3652KB → 184KB（省 95%）。
- `src/icons.js` + `test/core/icons-coverage.test.js`：图标名单与其覆盖率守卫
  （漏注册只会静默不渲染，编译/类型/普通单测都发现不了）。
- `src/utils/image.js` + `test/core/image-util.test.js`（13 用例）。

### 变更（性能数据）

| 指标 | 改造前 | 改造后 |
|---|---|---|
| 首屏 load（冷缓存，国内） | 约 60~120 s（用户体感） | **4.5 s** |
| 主 JS | 1.22MB 单文件 / gzip 404KB | 14KB + 3 个可缓存 vendor 块 |
| 上传头像 | 1.33MB | **43KB** |
| 存量图片合计 | 3652 KB | **184 KB**（省 95%） |
| 图片可用性 | 头像全部返回 HTML（不可用） | 36/36 正常加载 |

详见 [`docs/问题排查与修复完整报告.md`](docs/问题排查与修复完整报告.md)（11 个问题的完整复盘）与 [`docs/线上性能优化报告.md`](docs/线上性能优化报告.md)。



### 修复

- **商品详情接口对不存在的 id 返回 `200 + data:null`**：`ProductServiceImpl` 为防缓存穿透
  对不存在的 id 返回 null（设计合理），但 Controller 直接 `Result.success(null)`，
  前端拿到「成功但无数据」⇒ 页面空白且无提示。现在 Controller 边界判空抛
  `BusinessException("商品不存在")` ⇒ `code=3005`。
  **契约变化**：`200 + data:null` → `200 + code=3005`（对老前端是改善：会弹提示而非空白）。
- **秒杀页分页器在 375px 下横向溢出**（`scrollWidth=422 > 375`）：手机端允许分页器换行、
  隐藏「每页条数」下拉、页码收窄；桌面端不受影响。
- **秒杀商品可经 API 加入购物车**：前端隐藏了按钮，后端 `CartServiceImpl#addToCart`
  却没校验（业务规则只做在 UI 层等于没做）；且因秒杀商品 `stock=0`，会返回误导性的
  「商品库存不足」。现改为库存校验前按 `isSeckill` 拦截，返回
  「秒杀商品仅支持秒杀通道购买，不可加入购物车」。
- **加购失败留下 quantity=0 幽灵条目**：回滚用 `increment(-quantity)`，商品原本不在
  购物车时减回去会留下 `value=0` 字段；而列表清理条件 `stock < quantity` 对 0 不成立，
  永远清不掉 ⇒ 购物车出现 0 件条目、角标计数虚增。现回滚区分「新增（HDEL）」与
  「累加失败（减回）」，列表补 `quantity <= 0` 判失效并自愈历史数据。

### 测试

- 前端单测 **19 文件 / 176 用例**（新增 54 个）：组件 props/事件/状态、移动端适配结构性守卫、
  商城边界、支付异常路径。
- 后端单测 **155 用例**（新增商品详情契约 2 例）。
- **新增真实前后端联调套件** `test/integration/live.test.js`（19 用例，环境变量驱动，
  已对生产全绿）：登录/身份、三角色权限矩阵、购物车/下单/查询、支付全链路、秒杀 8 并发。
- **新增可重复 UI 回归** `tools/ui_regression.py`（桌面 1366 + 手机 390，30 项）：
  逐页渲染断言 + 横向溢出探测，实测发现并修复了分页器溢出。
- **生产 API 回归扩展** `tools/regression_prod.py`（46 → 64 用例）：角色写操作矩阵、
  售后审核链路、负向边界。
- 修正测试基础设施保真度：`test/handlers.js` 的购物车删除/改数量/清空原先不改桩数据，
  导致「删完就空」这类行为永远测不出来。



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
