# 数据库初始化说明

本目录把数据库初始化拆成 **建库 / 结构 / 权限字典 / 商品目录 / 演示账号 / 环境分发 / 生产授权**
七层，让「开源给面试官看」和「真正上线」两个场景共用同一套文件，但按需取用。

## 一、一个容器，三套环境

PeakFlow 用**一个 MySQL 实例**承载三套环境，靠**库名**隔离，避免本机起三个 MySQL 容器：

| 库名 | 用途 | 灌入的内容 | 谁在连 |
|---|---|---|---|
| `peakflow_dev` | 本地开发 | 结构 + 权限字典 + 商品目录 + 演示账号 | `SPRING_PROFILES_ACTIVE=dev` |
| `peakflow_test` | 自动化测试 | 结构 + 权限字典 + 商品目录（**不含演示密码**） | `SPRING_PROFILES_ACTIVE=test` |
| `peakflow_prod` | 生产 | 结构 + 权限字典 + 商品目录（账号另行授权） | `SPRING_PROFILES_ACTIVE=prod` |

> `peakflow_test` 必须带**权限字典**（`02`）：注册接口会给新用户分配 `role_id=1`，
> 而 `user_role` 有外键指向 `role(id)`。三张权限表为空时注册会外键失败、
> 整个事务回滚，集成测试全挂（历史踩坑，见 `backend/src/test/README.md`）。

## 二、目录结构

```
deploy/mysql/
├── init/                          # 容器首次初始化时自动执行（按文件名字典序）
│   ├── 00-create-databases.sql    # 建 peakflow_dev / _test / _prod 三个库
│   ├── 01-schema.sql              # 17 张表结构                 ← 三套环境共用
│   ├── 02-base-data.sql           # 权限/角色字典               ← 三套环境共用
│   ├── 03-product-data.sql        # 分类 + 商品目录             ← 三套环境共用
│   ├── 04-demo-account.sql        # 演示账号（仅灌入 dev）      ← 仅演示
│   └── 05-provision-envs.sh       # 把 01~03 再灌进 test / prod
├── prod/
│   └── grant-admin-role.sql       # 生产账号授权（4 个角色通用）← 仅生产，手工执行
└── README.md
```

| 文件 | 内容 | 行数 | dev | test | prod |
|---|---|---:|:---:|:---:|:---:|
| `00-create-databases.sql` | `CREATE DATABASE` × 3（utf8mb4 / utf8mb4_0900_ai_ci） | 30 | ✅ | ✅ | ✅ |
| `01-schema.sql` | 17 张表 `CREATE TABLE`，无数据 | 419 | ✅ | ✅ | ✅ |
| `02-base-data.sql` | `permission`(11) / `role`(4) / `role_permission`(21) | 81 | ✅ | ✅ | ✅ |
| `03-product-data.sql` | `category`(10) / `product`(100，含 60 秒杀) | 163 | ✅ | ✅ | ✅ |
| `04-demo-account.sql` | `user`(5) / `user_role`(6) | 63 | ✅ | ❌ | ❌ |
| `05-provision-envs.sh` | 驱动脚本：把 `01~03` 灌进 test / prod，并打印各库行数自检 | 70 | — | — | — |
| `prod/grant-admin-role.sql` | 把账号设置成指定角色（`admin` / `product_ops` / `order_service` / `user`） | 116 | ❌ | ❌ | ✅ |

> `grant-admin-role.sql` 文件名沿用了部署规范，但**并不只能授 admin** ——
> 改参数区的 `@role_code` 即可授任意一个角色。

**为什么这样切分**

- `01` + `02` 是「没有它系统就跑不起来」的部分——缺了权限字典，`@PreAuthorize` 全部失效。
- `03` 是**商品目录**：生产环境同样需要一份初始商品，所以和 `01`/`02` 一样共用。
  商品的图片、文案、价格都是真实的演示数据（Unsplash 商用实拍图），生产可直接沿用再逐步替换。
- `04` 是**唯一含密码的文件**，所以单独拆出来，生产不加载即可。

为什么 `00` 与 `05` 单独拆出来：

- `01~04` 是 `mysqldump` 产物，**内部没有 `USE` 语句**，只能写入「当前默认库」。
  容器启动时 `MYSQL_DATABASE=peakflow_dev`，所以它们自然落在 dev 库；
  test / prod 需要在 `05-provision-envs.sh` 里显式指定目标库再灌一遍。
- 如果改成在每个 sql 顶部手写 `USE peakflow_dev;` ——
  同一份 dump 就无法复用于另外两套环境，失去了「三套环境共用一份结构」的意义。

> 依赖顺序：`00` → `01` → `02` → `03` → `04` → `05`。文件名的数字前缀就是 Docker 初始化脚本的执行顺序
> （镜像按文件名字典序执行）。其中 `03` 内部 `category` 必须先于 `product` 插入
> （`product.category_id` 引用 `category.id`）；`04` 内部 `user` 必须先于 `user_role`
> （且 `user_role.role_id` 依赖 `02` 里的 `role`）。
>
> `01-schema.sql` 每张表前都有 `DROP TABLE IF EXISTS`，因此 `05` 重复执行是安全的（幂等）。

---

## 三、加载哪些文件：三个场景对比

| | `peakflow_dev`（演示/面试官） | `peakflow_test`（自动化测试） | `peakflow_prod`（生产上线） |
|---|---|---|---|
| 加载文件 | `00` + `01` + `02` + `03` + `04` | `00` + `01` + `02` + `03` | `00` + `01` + `02` + `03` |
| 商品 | 100 条（含 60 个秒杀） | 100 条（同上） | 100 条（秒杀是否保留由你决定，见下） |
| 账号 | 5 个演示账号（密码公开） | **无**，集成测试自建后清理 | **无**，自行注册 |
| 管理员来源 | `04-demo-account.sql` 内置 | 测试里 `grantAdmin()` 直接写 `user_role` | 注册接口 + `grant-admin-role.sql` |

> 三套环境的差异只有一处：**只有 dev 灌演示账号**（`04`），
> 因为它含公开密码，进了 test / prod 就是纯粹的安全负债。

### 生产环境要不要保留秒杀标记？

`03-product-data.sql` 里 60 个商品的 `is_seckill = 1`，时间窗口是相对的
（加载时刻前 1 小时 → 后 365 天）。两种做法：

- **想让线上也直接有秒杀活动可展示** → 保持原样，什么都不用做
- **想让秒杀由运营在管理后台自行创建** → 加载后执行一次：

```sql
UPDATE product SET is_seckill = 0, seckill_price = NULL, seckill_stock = 0,
       seckill_start_time = NULL, seckill_end_time = NULL;
```

100 条商品会全部保留，只是不再标记为秒杀。

---

## 四、管理用户：三个场景怎么分配

核心思路是**把「账号数据」和「授权动作」彻底分开**——这样仓库里永远不会有生产密码，
演示又能开箱即用。

### 演示账号（在 `04-demo-account.sql`，生产禁用）

| 账号 | 密码 | 角色 | 权限范围 |
|---|---|---|---|
| `pfadmin` | `Admin123456` | 超级管理员 | 全部 11 个权限点 |
| `sysadmin` | `Admin123456` | 超级管理员 | 全部 11 个权限点 |
| `productops` | `Ops123456` | 商品运营 | 商品/分类/秒杀/上传 + 看板 |
| `ordersvc` | `Service123456` | 订单客服 | 订单查询/发货 + 退款审核/查询 + 看板 |
| `malluser` | `User123456` | 普通用户 | 仅自己的订单/地址/购物车 |

> 商城端登录用 `malluser`，管理端（`/admin/login`）用 `pfadmin`。

### 生产账号：三步创建，密码全程不落仓库

```bash
# ① 注册账号（密码由应用 BCrypt 加密后落库，明文不落地、不进仓库）
curl -X POST http://<你的域名>/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"opsadmin","password":"<你的强密码>","email":"opsadmin@your-domain.com","nickname":"运营管理员"}'

# ② 授权（打开 prod/grant-role.sql，改开头两个变量后执行）
#     SET @username  = 'opsadmin';
#     SET @role_code = 'admin';
docker exec -i <mysql容器名> mysql -uroot -p<密码> <库名> < deploy/mysql/prod/grant-role.sql

# ③ 验证：脚本末尾会输出该账号的角色与权限清单
```

`grant-role.sql` **支持全部 4 个角色**，改 `@role_code` 即可：

| `@role_code` | 角色 | 权限数 | 能做什么 |
|---|---|---:|---|
| `admin` | 超级管理员 | 11 | 全部管理权限 |
| `product_ops` | 商品运营 | 5 | 商品/分类/秒杀管理 + 图片上传 + 看板；看不到订单和用户 |
| `order_service` | 订单客服 | 5 | 订单查询/发货 + 退款查询/审核 + 看板；不能作废退款 |
| `user` | 普通用户 | 0 | 仅自己的订单、地址、购物车 |

所以生产环境可以按岗位建多个账号，例如 `opsadmin` → `admin`、
`products` → `product_ops`、`service` → `order_service`，各建一次、各跑一次脚本即可。

**脚本的三个安全设计**（都实测过）：

- **覆盖式而非追加** —— 结果确定、可重复执行。实测连跑多次，角色关联始终只有 1 条
- **编码写错不会破坏现有授权** —— `@role_code` 填了不存在的值时，脚本先输出
  `[ERROR] 角色编码无效: xxx —— 可选值: ...`，随后自动跳过清空与授权动作，
  账号原有角色**原封不动**（已实测：账号保持 `admin` 不变）
- **账号名写错会输出空结果** —— 校验查询只返回表头、无数据行，一眼能看出没生效

> 新注册用户默认被分配 `role_id = 1`（普通用户），见
> `backend/src/main/java/com/zhou/peakflow/mapper/UserMapper.java#userRole`。
> 脚本会把它替换成你指定的角色，不会残留默认的 `user` 角色。

---

## 五、容器怎么加载这些文件

Docker 官方 MySQL 镜像会在**数据卷首次初始化时**自动执行
`/docker-entrypoint-initdb.d/` 下的 `*.sql`（按文件名字典序）。
数据卷已存在时不会重复执行——**想重新初始化必须先删卷**。

`docker-compose.yml`（演示）：

```yaml
mysql:
  image: mysql:8.0
  environment:
    MYSQL_ROOT_PASSWORD: ${DB_PASSWORD}
    MYSQL_DATABASE: ${DB_NAME}
    TZ: Asia/Shanghai
  volumes:
    - mysql-data:/var/lib/mysql
    - ./deploy/mysql/init/01-schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro
    - ./deploy/mysql/init/02-base-data.sql:/docker-entrypoint-initdb.d/02-base-data.sql:ro
    - ./deploy/mysql/init/03-product-data.sql:/docker-entrypoint-initdb.d/03-product-data.sql:ro
    - ./deploy/mysql/init/04-demo-account.sql:/docker-entrypoint-initdb.d/04-demo-account.sql:ro
```

`docker-compose.prod.yml`（生产覆盖）**不挂 `04-demo-account.sql`**：

```yaml
mysql:
  volumes:
    - mysql-data:/var/lib/mysql
    - ./deploy/mysql/init/01-schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro
    - ./deploy/mysql/init/02-base-data.sql:/docker-entrypoint-initdb.d/02-base-data.sql:ro
    - ./deploy/mysql/init/03-product-data.sql:/docker-entrypoint-initdb.d/03-product-data.sql:ro
```

> 注意：不要用 `- ./deploy/mysql/init:/docker-entrypoint-initdb.d` 整目录挂载，
> 那样两个场景无法区分。逐个文件挂才能按场景取舍。

重新初始化（**会清空全部数据**）：

```bash
docker compose down -v && docker compose up -d
```

---

## 六、秒杀时间窗口为什么是相对时间

`03-product-data.sql` 里 60 个秒杀商品的 `seckill_start_time` / `seckill_end_time`
写的是 **`DATE_SUB(NOW(), INTERVAL 1 HOUR)`** 和 **`DATE_ADD(NOW(), INTERVAL 365 DAY)`**，
而不是固定日期。

原因：秒杀列表接口只返回**当前处于时间窗口内**的商品。如果写死日期，
种子文件生成后过一两天，面试官再加载就会发现秒杀页是空的——
而秒杀正是这个项目的头牌功能。改成相对时间后，任何时刻加载都能看到
60 个「进行中」的秒杀商品。

> 若想演示「未开始 / 已结束」状态，把个别行的表达式改成
> `DATE_ADD(NOW(), INTERVAL 1 DAY)`（未开始）或 `DATE_SUB(NOW(), INTERVAL 1 DAY)`（已结束）。

---

## 七、重新生成种子文件（结构或数据变了时）

```bash
DUMP="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe"   # Windows 下 MySQL 客户端路径
BASE="--default-character-set=utf8mb4 --single-transaction --skip-comments \
      --set-gtid-purged=OFF --no-tablespaces"
DATA="--no-create-info --complete-insert --skip-extended-insert"

# 1) 表结构
"$DUMP" -uroot -p --no-data peakflow $BASE > deploy/mysql/init/01-schema.sql

# 2) 权限字典
"$DUMP" -uroot -p $DATA $BASE peakflow permission role role_permission \
        > deploy/mysql/init/02-base-data.sql

# 3) 商品目录（注意 category 在前，product 依赖它）
"$DUMP" -uroot -p $DATA $BASE peakflow category product \
        > deploy/mysql/init/03-product-data.sql

# 4) 演示账号
"$DUMP" -uroot -p $DATA $BASE peakflow user user_role \
        > deploy/mysql/init/04-demo-account.sql
```

生成后**必须做三件事**：
1. 给每个文件补回顶部的说明头（`mysqldump` 会覆盖掉）
2. `03-product-data.sql` 里把秒杀时间窗口改回相对时间表达式
3. 把四个文件里的 `SET TIME_ZONE='+00:00'` 改成 `'+08:00'`

### 第 2、3 点为什么必须做

**时间窗口**：见上一节，写死日期会导致秒杀页在某一天之后变空。

**时区**：`mysqldump` 会在每个文件顶部注入 `/*!40103 SET TIME_ZONE='+00:00' */;`，
把**会话时区改成 UTC**。这会让 `03-product-data.sql` 里的 `NOW()` 按 UTC 计算，
而应用 JDBC 连接串写的是 `serverTimezone=Asia/Shanghai`——两边口径差 8 小时，
秒杀时间窗口会整体偏移。改成 `'+08:00'` 后 `NOW()` 按北京时间求值，
且不依赖部署机器的系统时区（数字偏移量无需 MySQL 时区表支持）。

验证方法（加载后立刻查，开始时间应约等于「当前时间 − 1 小时」）：

```sql
SELECT NOW() AS 当前北京时间, MIN(seckill_start_time) AS 秒杀最早开始
FROM product WHERE is_seckill = 1;
```

---

## 八、已知注意事项

- **`backend/postman/seed_base_data.py` 里有硬编码的本地库密码 `1234`**。
  那是本地开发脚本，开源前建议改成读环境变量（`os.environ.get('DB_PASSWORD')`）。
- **秒杀缓存预热**：应用启动时**不会**自动把秒杀库存写进 Redis，但秒杀下单逻辑
  遇到库存 Key 缺失会从 DB 重建（`SECKILL_STOCK_KEY` 缺失返回 `-2` 再重试），
  所以首次下单能自愈。想预热全量的话，用管理员调用 `GET /seckill/preheat/all`。
- **`product` 表在 `information_schema.TABLE_ROWS` 里显示约 50，实际是 100 行**——
  InnoDB 的行数是估算值，不要用它做校验，请用 `SELECT COUNT(*)`。
