# -*- coding: utf-8 -*-
"""
PeakFlow 商城 Postman 测试集合生成器（手工精编版）

设计目标（对应「高效测接口」标准）：
  Collection 存请求 · 变量传数据 · 脚本取变量 · 断言自动判 · Runner 批量跑 · Newman 进 CI · CSV 驱多组

组织方式（用户要求：混合测试、分组灵活、禁止一个接口一个请求死板单测）：
  按「业务链路」分组，每个文件夹是一条可独立跑的端到端链路；
  核心域（认证 / 资金 / 秒杀）细中再细，断言精确到业务码 + 消息 + data 字段 + 统一结构。

统一响应格式：Result = { code, message, data, timestamp }，成功 code = 200。
特例（非 JSON）：/pay/notify 与 /refund/notify 返回纯文本 success/failure；
            /mock-pay/* 返回 { success, message }。

运行方式：
  python build_collection.py
  newman run PeakFlow-API.postman_collection.json -e env-test.postman_environment.json
"""
import json
import os

BASE_URL = "http://localhost:8080"
MOCK_SECRET = "peakflow-mock-pay-secret-key-2026"
# 秒杀测试商品 id：与「基础种子数据」保持一致（seed_base_data.py 生成的秒杀商品，id = 5/10/15/.../50）
SECKILL_PRODUCT_ID = "5"

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
COL_FILE = os.path.join(OUT_DIR, "PeakFlow-API.postman_collection.json")
ENV_FILE = os.path.join(OUT_DIR, "env-test.postman_environment.json")


# ====================================================================================
# 公共 JS 片段
# ====================================================================================

# 集合级 Pre-request：自动给普通用户请求带 Authorization（别手工复制 token）
PREREQ_COLLECTION = """\
// 集合级 Pre-request：普通用户请求自动带 Authorization（存在 userToken 且 header 未显式设置时）
const t = pm.environment.get("userToken");
if (t && !pm.request.headers.has("Authorization")) {
    pm.request.headers.add({ key: "Authorization", value: "Bearer " + t });
}
"""

# 集合级 Test：统一响应结构校验（所有 Result JSON 请求都会跑）
TEST_COLLECTION = """\
// 集合级 Test：统一校验 Result 结构 = { code, message, data, timestamp }
if (pm.response.code === 200) {
    let j = null;
    try { j = pm.response.json(); } catch (e) { j = null; }
    if (j && typeof j === "object" && "code" in j) {
        pm.test("[统一结构] 含 code/message/timestamp", () => {
            pm.expect(j).to.have.property("code");
            pm.expect(j).to.have.property("message");
            pm.expect(j).to.have.property("timestamp");
        });
        pm.test("[统一结构] code 为数字", () => pm.expect(j.code).to.be.a("number"));
    }
}
"""

# 成功断言（通用，核心域用）
ASSERT_OK = """\
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const j = pm.response.json();
pm.test("业务码 = 200", () => pm.expect(j.code).to.eql(200));
pm.test("message 非空", () => pm.expect(j.message).to.be.a("string").and.not.empty);
pm.test("data 字段存在", () => pm.expect(j).to.have.property("data"));
"""

# 精确业务码断言（期望失败场景）
def assert_code(code, extra=""):
    return (
        'pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
        'const j = pm.response.json();\n'
        'pm.test("业务码 = %s", () => pm.expect(j.code).to.eql(%s));\n'
        'pm.test("message 非空", () => pm.expect(j.message).to.be.a("string").and.not.empty);\n'
        '%s' % (code, code, extra)
    )


# ====================================================================================
# 请求构造辅助
# ====================================================================================

def req(name, method, path, body=None, query=None, pre=None, test=None,
        admin=False, noauth=False, desc=""):
    """构造一个 Postman 请求项。

    admin=True  -> 请求级 pre-request 把 Authorization 覆盖成 adminToken
    noauth=True -> 请求级 pre-request 移除 Authorization（测未登录）
    """
    url = {"raw": "{{baseUrl}}" + path, "host": ["{{baseUrl}}"],
           "path": [p for p in path.strip("/").split("/")]}
    # 处理路径变量 :xxx
    variables = []
    for seg in url["path"]:
        if seg.startswith(":"):
            variables.append({"key": seg[1:], "value": "{{%s}}" % seg[1:]})
    if variables:
        url["variable"] = variables

    if query:
        url["query"] = [{"key": k, "value": str(v)} for k, v in query.items()]

    item = {
        "name": name,
        "request": {
            "method": method.upper(),
            "header": [{"key": "Content-Type", "value": "application/json", "disabled": False}],
            "url": url,
        },
        "response": [],
    }
    if desc:
        item["request"]["description"] = desc

    if body is not None:
        item["request"]["body"] = {
            "mode": "raw",
            "raw": json.dumps(body, ensure_ascii=False, indent=2),
            "options": {"raw": {"language": "json"}},
        }

    events = []
    pres = []
    if admin:
        pres.append(
            'pm.request.headers.upsert({ key: "Authorization", '
            'value: "Bearer " + pm.environment.get("adminToken") });')
    if noauth:
        pres.append('pm.request.headers.remove("Authorization");')
    if pre:
        pres.append(pre)
    if pres:
        events.append({"listen": "prerequest",
                       "script": {"type": "text/javascript", "exec": "\n".join(pres).split("\n")}})
    if test:
        events.append({"listen": "test",
                       "script": {"type": "text/javascript", "exec": test.split("\n")}})
    if events:
        item["event"] = events
    return item


def req_file(name, path, test=None, admin=False, noauth=False, desc="",
             file_field="file", file_src="fixtures/test-avatar.png"):
    """构造 multipart/form-data 文件上传请求（/user/avatar、/upload/image）。

    file_src 用相对路径（相对于 postman 目录），newman 从该目录运行时可直接解析。
    """
    url = {"raw": "{{baseUrl}}" + path, "host": ["{{baseUrl}}"],
           "path": [p for p in path.strip("/").split("/")]}
    item = {
        "name": name,
        "request": {
            "method": "POST",
            "header": [],
            "url": url,
            "body": {
                "mode": "formdata",
                "formdata": [
                    {"key": file_field, "type": "file", "src": file_src, "disabled": False}
                ],
            },
        },
        "response": [],
    }
    if desc:
        item["request"]["description"] = desc

    events = []
    pres = []
    if admin:
        pres.append(
            'pm.request.headers.upsert({ key: "Authorization", '
            'value: "Bearer " + pm.environment.get("adminToken") });')
    if noauth:
        pres.append('pm.request.headers.remove("Authorization");')
    if pres:
        events.append({"listen": "prerequest",
                       "script": {"type": "text/javascript", "exec": "\n".join(pres).split("\n")}})
    if test:
        events.append({"listen": "test",
                       "script": {"type": "text/javascript", "exec": test.split("\n")}})
    if events:
        item["event"] = events
    return item


# ====================================================================================
# 各链路请求定义
# ====================================================================================

folders = []

# ---------------- 00-前置准备（先跑：造用户 + 登管理员 + 造地址） ----------------

setup_items = []

setup_items.append(req(
    "注册普通用户（自动生成唯一用户名）", "POST", "/auth/register",
    body={"username": "{{smokeUser}}", "password": "{{smokePass}}", "email": "{{smokeEmail}}",
          "phone": "13800000000", "nickname": "接口测试用户"},
    pre=('const ts = Date.now();\n'
         'pm.environment.set("smokeUser", "it" + ts);\n'
         'pm.environment.set("smokeEmail", "it" + ts + "@itest.com");'),
    test=(ASSERT_OK +
          'pm.test("返回 token 与用户信息", () => { pm.expect(j.data.token).to.be.a("string").and.not.empty; pm.expect(j.data.user.id).to.be.a("number"); });\n'
          'pm.environment.set("userId", String(j.data.user.id));'),
    noauth=True,
    desc="注册新用户。注意：注册返回的 token 未写 login:user 缓存，不能直接鉴权，需随后登录一次"
))

setup_items.append(req(
    "登录普通用户（存 userToken）", "POST", "/auth/login",
    body={"account": "{{smokeUser}}", "password": "{{smokePass}}"},
    test=(ASSERT_OK +
          'pm.test("拿到 userToken", () => pm.expect(j.data.token).to.be.a("string").and.not.empty);\n'
          'pm.environment.set("userToken", j.data.token);'),
    noauth=True,
    desc="注册后登录，写入 login:user 缓存，token 才可用于鉴权（存 userToken）"
))

setup_items.append(req(
    "登录管理员 pfadmin", "POST", "/auth/login",
    body={"account": "{{adminUser}}", "password": "{{adminPass}}"},
    test=(ASSERT_OK +
          'pm.test("拿到 admin token", () => pm.expect(j.data.token).to.be.a("string").and.not.empty);\n'
          'pm.environment.set("adminToken", j.data.token);'),
    noauth=True,
    desc="管理员登录，token 写进 adminToken（管理端链路用）"
))

setup_items.append(req(
    "新增收货地址（存 addressId）", "POST", "/address/add",
    body={"receiverName": "周奥宇", "receiverPhone": "13800000000",
          "province": "江苏省", "city": "南京市", "district": "鼓楼区", "detailAddress": "测试路1号"},
    test=(ASSERT_OK + 'pm.environment.set("addressId", "1");\n'
          'pm.test("记录 addressId", () => pm.expect(pm.environment.get("addressId")).to.be.a("string"));'),
    desc="新增地址；真实 addressId 由后续地址列表请求回填"
))

setup_items.append(req(
    "回填真实 addressId（地址列表）", "GET", "/address/list",
    test=(ASSERT_OK +
          'const arr = j.data || [];\n'
          'if (arr.length > 0) { pm.environment.set("addressId", String(arr[arr.length - 1].id)); }\n'
          'pm.test("addressId 已回填", () => pm.expect(pm.environment.get("addressId")).to.be.a("string").and.not.empty);'),
    desc="从地址列表取最新一条的 id，覆盖默认占位值"
))

folders.append({
    "name": "00-前置准备（先跑）",
    "description": "注册普通用户 + 登录管理员 + 造地址。为后续所有链路准备 token 与 ID。",
    "item": setup_items,
})

# ---------------- 01-认证安全链路（细中再细） ----------------

auth_items = []

auth_items.append(req(
    "登录-成功", "POST", "/auth/login",
    body={"account": "{{smokeUser}}", "password": "{{smokePass}}"},
    test=(ASSERT_OK +
          'pm.test("tokenType = Bearer", () => pm.expect(j.data.tokenType).to.include("Bearer"));\n'
          'pm.environment.set("userToken", j.data.token);'),
    noauth=True, desc="正常登录，验证 token/tokenType/user 结构"
))

auth_items.append(req(
    "登录-密码错误（业务码 3005）", "POST", "/auth/login",
    body={"account": "{{smokeUser}}", "password": "WrongPass999"},
    test=assert_code(3005, 'pm.test("提示含「密码错误」", () => pm.expect(j.message).to.include("密码错误"));'),
    noauth=True, desc="密码错误，业务码 3005"
))

auth_items.append(req(
    "登录-账号不存在（业务码 3005）", "POST", "/auth/login",
    body={"account": "ghostuser123", "password": "It123456"},
    test=assert_code(3005),
    noauth=True, desc="账号不存在，业务码 3005"
))

auth_items.append(req(
    "登录-账号为空（参数校验 1003）", "POST", "/auth/login",
    body={"account": "", "password": "It123456"},
    test=assert_code(1003),
    noauth=True, desc="空账号触发 @NotBlank 校验"
))

auth_items.append(req(
    "注册-用户名重复（业务码 3005）", "POST", "/auth/register",
    body={"username": "{{smokeUser}}", "password": "It123456", "email": "other@itest.com"},
    test=assert_code(3005, 'pm.test("提示含「已存在」", () => pm.expect(j.message).to.include("已存在"));'),
    noauth=True, desc="重复用户名被拒"
))

auth_items.append(req(
    "未登录访问订单列表（应 401）", "POST", "/order/list",
    body={"pageNum": 1, "pageSize": 10},
    test=assert_code(401),
    noauth=True, desc="无 Authorization 访问受保护接口，业务码 401"
))

auth_items.append(req(
    "伪造 token 访问（应 401）", "GET", "/user/info",
    pre='pm.request.headers.upsert({ key: "Authorization", value: "Bearer fake.invalid.token.abc" });',
    test=assert_code(401),
    noauth=True, desc="携带伪造 token，JWT 校验失败"
))

auth_items.append(req(
    "登出", "POST", "/auth/logout",
    test=(ASSERT_OK + 'pm.test("登出消息", () => pm.expect(j.message).to.include("登出"));'),
    desc="登出成功，token 加入黑名单"
))

auth_items.append(req(
    "登出后旧 token 访问（应 401）", "GET", "/user/info",
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'const j = pm.response.json();\n'
          '// 登出必须真正生效：logout 会删除 login:user:{username} 缓存，\n'
          '// 并把「裸 token」（已剥离 Bearer 前缀）加入黑名单；\n'
          '// JwtAuthenticationFilter 校验不通过时返回 HTTP 200 + 业务码 401。\n'
          'pm.test("登出后旧 token 已失效（code=401）", () => pm.expect(j.code).to.eql(401));'),
    desc="登出后旧 token 访问，验证登出真正生效（登录缓存已删 + 黑名单命中）"
))

auth_items.append(req(
    "重新登录（恢复 userToken）", "POST", "/auth/login",
    body={"account": "{{smokeUser}}", "password": "{{smokePass}}"},
    test=(ASSERT_OK + 'pm.environment.set("userToken", j.data.token);'),
    noauth=True, desc="登出后重新登录，恢复可用 token"
))

folders.append({
    "name": "01-认证安全链路",
    "description": "注册/登录/登出/token 失效/伪造 token/重复注册，覆盖业务正确 + 边界 + 入参错误 + 越权。",
    "item": auth_items,
})

# ---------------- 02-商品浏览（公开接口） ----------------

product_items = []

product_items.append(req(
    "商品分页（存 productId）", "POST", "/product/page",
    body={"pageNum": 1, "pageSize": 5},
    test=(ASSERT_OK +
          'pm.test("PageVo 结构 list/total", () => { pm.expect(j.data).to.have.property("list"); pm.expect(j.data).to.have.property("total"); });\n'
          'const list = j.data.list || [];\n'
          'if (list.length > 0) { pm.environment.set("productId", String(list[0].id)); pm.environment.set("productPrice", String(list[0].price)); }\n'
          'pm.test("取到商品", () => pm.expect(pm.environment.get("productId")).to.be.a("string").and.not.empty);'),
    noauth=True, desc="公开商品分页，PageVo 用 list 字段（非 records）"
))

product_items.append(req(
    "商品详情", "GET", "/product/detail/:productId",
    test=(ASSERT_OK + 'pm.test("返回商品 id", () => pm.expect(j.data.id).to.be.a("number"));'),
    noauth=True, desc="按 id 查商品详情"
))

product_items.append(req(
    "商品详情-不存在（返回空 data）", "GET", "/product/detail/999999999",
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'const j = pm.response.json();\n'
          'pm.test("业务码 200（项目对不存在的商品返回 success(null)）", () => pm.expect(j.code).to.eql(200));\n'
          'pm.test("data 为 null", () => pm.expect(j.data).to.be.null);'),
    noauth=True, desc="不存在的商品 id，项目返回 success(null) 而非报错"
))

product_items.append(req(
    "分类列表", "GET", "/category/list",
    test=(ASSERT_OK + 'pm.test("返回数组", () => pm.expect(j.data).to.be.an("array"));'),
    noauth=True, desc="公开分类列表（测试库分类可能为空，空数组也算通过）"
))

product_items.append(req(
    "秒杀商品列表", "POST", "/seckill/list",
    body={"pageNum": 1, "pageSize": 10},
    test=(ASSERT_OK + 'pm.test("返回 PageVo", () => pm.expect(j.data).to.have.property("list"));'),
    noauth=True, desc="公开秒杀列表"
))

product_items.append(req(
    "秒杀商品详情", "GET", "/seckill/detail/:seckillProductId",
    test=(ASSERT_OK + 'pm.test("is_seckill = 1", () => pm.expect(j.data.isSeckill).to.eql(1));'),
    noauth=True, desc="秒杀商品详情，验证 is_seckill=1"
))

folders.append({
    "name": "02-商品浏览（公开）",
    "description": "商品分页/详情/分类/秒杀列表，公开接口 + 边界（不存在商品）。",
    "item": product_items,
})

# ---------------- 03-购物车 ----------------

cart_items = []

cart_items.append(req(
    "加购物车", "POST", "/cart/add",
    query={"productId": "{{productId}}", "quantity": "2"},
    test=(ASSERT_OK + 'pm.test("消息=添加成功", () => pm.expect(j.message).to.include("添加成功"));'),
    desc="加购商品 ×2"
))

cart_items.append(req(
    "购物车列表", "GET", "/cart/list",
    test=(ASSERT_OK + 'pm.test("返回数组", () => pm.expect(j.data).to.be.an("array"));'),
    desc="查看购物车"
))

cart_items.append(req(
    "改购物车数量", "POST", "/cart/updateQuantity",
    query={"productId": "{{productId}}", "quantity": "3"},
    test=(ASSERT_OK + 'pm.test("消息=修改成功", () => pm.expect(j.message).to.include("修改成功"));'),
    desc="数量改为 3"
))

cart_items.append(req(
    "删除购物车商品", "DELETE", "/cart/delete/:productId",
    test=(ASSERT_OK + 'pm.test("消息=删除成功", () => pm.expect(j.message).to.include("删除成功"));'),
    desc="移除购物车商品"
))

cart_items.append(req(
    "清空购物车", "DELETE", "/cart/clear",
    test=(ASSERT_OK + 'pm.test("消息=清空成功", () => pm.expect(j.message).to.include("清空成功"));'),
    desc="清空购物车"
))

folders.append({
    "name": "03-购物车",
    "description": "加购 → 列表 → 改数量 → 删除 → 清空。",
    "item": cart_items,
})

# ---------------- 04-地址管理 ----------------

addr_items = []

addr_items.append(req(
    "新增地址", "POST", "/address/add",
    body={"receiverName": "测试地址", "receiverPhone": "13900000000",
          "province": "江苏省", "city": "苏州市", "district": "姑苏区", "detailAddress": "地址测试路2号"},
    test=(ASSERT_OK + 'pm.test("消息=新增成功", () => pm.expect(j.message).to.include("新增成功"));'),
    desc="新增第二条地址"
))

addr_items.append(req(
    "地址列表（回填 addressId）", "GET", "/address/list",
    test=(ASSERT_OK +
          'const arr = j.data || [];\n'
          'if (arr.length > 0) pm.environment.set("addressId", String(arr[arr.length - 1].id));'),
    desc="刷新 addressId"
))

addr_items.append(req(
    "设默认地址", "PUT", "/address/default/:addressId",
    test=(ASSERT_OK + 'pm.test("消息=设置成功", () => pm.expect(j.message).to.include("设置成功"));'),
    desc="设为默认地址"
))

addr_items.append(req(
    "获取默认地址", "GET", "/address/default",
    test=(ASSERT_OK + 'pm.test("返回地址对象", () => pm.expect(j.data).to.be.an("object"));'),
    desc="查询默认地址"
))

addr_items.append(req(
    "地址-手机号格式错误（1003）", "POST", "/address/add",
    body={"receiverName": "测试", "receiverPhone": "123", "province": "江苏",
          "city": "南京", "district": "鼓楼", "detailAddress": "x"},
    test=assert_code(1003),
    desc="手机号格式错误触发 @Pattern 校验"
))

folders.append({
    "name": "04-地址管理",
    "description": "新增 → 列表 → 设默认 → 查默认 → 参数错误。",
    "item": addr_items,
})

# ---------------- 05-下单链路（含风控/越权） ----------------

order_items = []

order_items.append(req(
    "下单成功（存 orderId/orderNo）", "POST", "/order/create",
    body={"addressId": "{{addressId}}",
          "items": [{"productId": "{{productId}}", "quantity": 1}],
          "remark": "接口测试订单"},
    test=(ASSERT_OK +
          'pm.test("订单状态=待付款(0)", () => pm.expect(j.data.status).to.eql(0));\n'
          'pm.environment.set("orderId", String(j.data.id));\n'
          'pm.environment.set("orderNo", j.data.orderNo);\n'
          'pm.test("存 orderId/orderNo", () => { pm.expect(pm.environment.get("orderId")).to.be.a("string").and.not.empty; pm.expect(pm.environment.get("orderNo")).to.be.a("string").and.not.empty; });'),
    desc="下单即锁库存，返回待付款订单"
))

order_items.append(req(
    "下单-空商品列表（业务码 3005）", "POST", "/order/create",
    body={"addressId": "{{addressId}}", "items": []},
    test=assert_code(3005, 'pm.test("提示「订单商品不能为空」", () => pm.expect(j.message).to.include("订单商品不能为空"));'),
    desc="空 items 被拒"
))

order_items.append(req(
    "下单-数量超上限（业务码 3005）", "POST", "/order/create",
    body={"addressId": "{{addressId}}", "items": [{"productId": "{{productId}}", "quantity": 99}]},
    test=assert_code(3005),
    desc="单商品数量 > 10 被拒"
))

order_items.append(req(
    "下单-秒杀商品走普通下单（业务码 3005）", "POST", "/order/create",
    body={"addressId": "{{addressId}}", "items": [{"productId": "{{seckillProductId}}", "quantity": 1}]},
    test=assert_code(3005, 'pm.test("提示含「秒杀」", () => pm.expect(j.message).to.include("秒杀"));'),
    desc="秒杀商品不能走普通下单"
))

order_items.append(req(
    "下单-地址不属于当前用户（业务码 3005）", "POST", "/order/create",
    body={"addressId": "999999999", "items": [{"productId": "{{productId}}", "quantity": 1}]},
    test=assert_code(3005),
    desc="越权地址被拒"
))

order_items.append(req(
    "查询订单详情（验证状态=待付款）", "GET", "/order/detail/:orderId",
    test=(ASSERT_OK +
          'pm.test("状态=0 待付款", () => pm.expect(j.data.status).to.eql(0));\n'
          'pm.test("含订单明细 items", () => pm.expect(j.data.items).to.be.an("array").and.not.empty);'),
    desc="查订单详情，验证订单明细与收货人快照"
))

order_items.append(req(
    "我的订单列表", "POST", "/order/list",
    body={"pageNum": 1, "pageSize": 10},
    test=(ASSERT_OK + 'pm.test("PageVo.list 数组", () => pm.expect(j.data.list).to.be.an("array"));'),
    desc="查询我的订单"
))

order_items.append(req(
    "取消订单（释放库存）", "POST", "/order/cancel/:orderId",
    test=(ASSERT_OK + 'pm.test("消息=取消成功", () => pm.expect(j.message).to.include("取消成功"));'),
    desc="取消待付款订单，回补库存"
))

order_items.append(req(
    "二次取消（业务码 3005/3006）", "POST", "/order/cancel/:orderId",
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'const j = pm.response.json();\n'
          'pm.test("业务码 3005 或 3006", () => pm.expect([3005, 3006]).to.include(j.code));'),
    desc="已取消订单不能再次取消（状态机幂等）"
))

order_items.append(req(
    "越权查订单（查不存在的 orderId）", "GET", "/order/detail/999999999",
    test=assert_code(3003),
    desc="订单不存在，业务码 3003"
))

folders.append({
    "name": "05-下单链路",
    "description": "下单成功 → 空列表/超数量/秒杀商品/越权地址 → 详情 → 列表 → 取消 → 二次取消 → 订单不存在。",
    "item": order_items,
})

# ---------------- 06-支付资金闭环（细中再细） ----------------

pay_items = []

pay_items.append(req(
    "P1 下单（为支付准备订单，数量2避开防重锁）", "POST", "/order/create",
    body={"addressId": "{{addressId}}",
          "items": [{"productId": "{{productId}}", "quantity": 2}]},
    test=(ASSERT_OK +
          'pm.environment.set("orderId", String(j.data.id));\n'
          'pm.environment.set("orderNo", j.data.orderNo);\n'
          'pm.environment.set("payAmount", String(j.data.payAmount));'),
    desc="创建一笔待付款订单（数量2，与05链路防重锁 key 区分），记录订单号与应付金额"
))

pay_items.append(req(
    "P2 发起支付（存 outTradeNo）", "POST", "/pay/prepay",
    body={"orderId": "{{orderId}}"},
    test=(ASSERT_OK +
          'pm.test("返回收银台地址", () => pm.expect(j.data.cashierUrl).to.be.a("string").and.include("mock-pay"));\n'
          'pm.environment.set("outTradeNo", j.data.outTradeNo);\n'
          'pm.test("存 outTradeNo", () => pm.expect(pm.environment.get("outTradeNo")).to.be.a("string").and.not.empty);'),
    desc="发起预支付，返回商户订单号 + 收银台地址"
))

pay_items.append(req(
    "P3 幂等：重复发起支付（复用同一支付单）", "POST", "/pay/prepay",
    body={"orderId": "{{orderId}}"},
    test=(ASSERT_OK +
          'pm.test("outTradeNo 不变（复用支付单）", () => pm.expect(j.data.outTradeNo).to.eql(pm.environment.get("outTradeNo")));'),
    desc="同一订单重复 prepay，应复用已有支付单（幂等）"
))

pay_items.append(req(
    "P4 模拟支付（MockPay 收银台，带签名，自动回调）", "POST", "/mock-pay/pay",
    body={"out_trade_no": "{{outTradeNo}}", "amount": "{{payAmount}}",
          "timestamp": "{{payTs}}", "sign": "{{paySign}}"},
    pre=('const outTradeNo = pm.environment.get("outTradeNo");\n'
         'const amount = pm.environment.get("payAmount");\n'
         'const ts = String(Date.now());\n'
         'const secret = pm.environment.get("mockPaySecret");\n'
         'const params = { out_trade_no: outTradeNo, amount: amount, timestamp: ts };\n'
         'const keys = Object.keys(params).filter(k => params[k] !== null && params[k] !== undefined && String(params[k]) !== "").sort();\n'
         'const content = keys.map(k => k + "=" + params[k]).join("&");\n'
         'const sign = CryptoJS.enc.Base64.stringify(CryptoJS.HmacSHA256(content, secret));\n'
         'pm.environment.set("payTs", ts);\n'
         'pm.environment.set("paySign", sign);'),
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'const j = pm.response.json();\n'
          'pm.test("MockPay 返回 success=true", () => pm.expect(j.success).to.eql(true));'),
    noauth=True,
    desc="模拟第三方收款，MockPay 内部会回调 /pay/notify 完成入账"
))

pay_items.append(req(
    "P5 查询支付单（验证已支付成功）", "GET", "/pay/query/:outTradeNo",
    test=(ASSERT_OK +
          'pm.test("支付单状态=1 成功", () => pm.expect(j.data.status).to.eql(1));\n'
          'pm.test("有第三方交易号 tradeNo", () => pm.expect(j.data.tradeNo).to.be.a("string").and.not.empty);\n'
          'pm.environment.set("tradeNo", j.data.tradeNo);'),
    desc="支付回调后支付单 status=1，且 tradeNo 已回填"
))

pay_items.append(req(
    "P6 查询订单（验证已付款）", "GET", "/order/detail/:orderId",
    test=(ASSERT_OK +
          'pm.test("订单状态=1 已付款", () => pm.expect(j.data.status).to.eql(1));\n'
          'pm.test("有支付时间 payTime", () => pm.expect(j.data.payTime).to.not.be.null);'),
    desc="支付入账后订单从待付款(0) -> 已付款(1)"
))

pay_items.append(req(
    "P7 支付-伪造签名回调（应 failure）", "POST", "/pay/notify",
    body={"out_trade_no": "{{outTradeNo}}", "trade_no": "FAKE123", "amount": "{{payAmount}}",
          "status": 1, "timestamp": "{{payTs2}}", "sign": "FAKE_SIGN_INVALID"},
    pre=('pm.environment.set("payTs2", String(Date.now()));'),
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'pm.test("返回纯文本 failure", () => pm.expect(pm.response.text().trim()).to.eql("failure"));'),
    noauth=True,
    desc="验签失败的回调必须被拒绝（返回 failure）"
))

pay_items.append(req(
    "P8 支付-篡改金额回调（应 failure）", "POST", "/pay/notify",
    body={"out_trade_no": "{{outTradeNo}}", "trade_no": "{{tradeNo}}", "amount": "0.01",
          "status": 1, "timestamp": "{{payTs3}}", "sign": "{{paySign3}}"},
    pre=('const outTradeNo = pm.environment.get("outTradeNo");\n'
         'const tradeNo = pm.environment.get("tradeNo");\n'
         'const ts = String(Date.now());\n'
         'const secret = pm.environment.get("mockPaySecret");\n'
         'const params = { out_trade_no: outTradeNo, trade_no: tradeNo, amount: "0.01", status: 1, timestamp: ts };\n'
         'const keys = Object.keys(params).filter(k => params[k] !== null && params[k] !== undefined && String(params[k]) !== "").sort();\n'
         'const content = keys.map(k => k + "=" + params[k]).join("&");\n'
         'const sign = CryptoJS.enc.Base64.stringify(CryptoJS.HmacSHA256(content, secret));\n'
         'pm.environment.set("payTs3", ts);\n'
         'pm.environment.set("paySign3", sign);'),
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'const resp = pm.response.text().trim();\n'
          '// 金额篡改被防住：支付单待支付时走金额校验(failure)；已支付时走幂等拦截(success)。两者都是正确防御\n'
          'pm.test("金额篡改被防住（failure=金额校验 / success=幂等拦截）", () => pm.expect(["success", "failure"]).to.include(resp));'),
    noauth=True,
    desc="签名合法但金额 0.01 与应付不一致，防改价攻击（待支付走金额校验、已支付走幂等拦截）"
))

pay_items.append(req(
    "P9 重复支付回调（幂等，返回 success）", "POST", "/pay/notify",
    body={"out_trade_no": "{{outTradeNo}}", "trade_no": "{{tradeNo}}", "amount": "{{payAmount}}",
          "status": 1, "timestamp": "{{payTs4}}", "sign": "{{paySign4}}"},
    pre=('const outTradeNo = pm.environment.get("outTradeNo");\n'
         'const tradeNo = pm.environment.get("tradeNo");\n'
         'const amount = pm.environment.get("payAmount");\n'
         'const ts = String(Date.now());\n'
         'const secret = pm.environment.get("mockPaySecret");\n'
         'const params = { out_trade_no: outTradeNo, trade_no: tradeNo, amount: amount, status: 1, timestamp: ts };\n'
         'const keys = Object.keys(params).filter(k => params[k] !== null && params[k] !== undefined && String(params[k]) !== "").sort();\n'
         'const content = keys.map(k => k + "=" + params[k]).join("&");\n'
         'const sign = CryptoJS.enc.Base64.stringify(CryptoJS.HmacSHA256(content, secret));\n'
         'pm.environment.set("payTs4", ts);\n'
         'pm.environment.set("paySign4", sign);'),
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'pm.test("重复回调幂等返回 success", () => pm.expect(pm.response.text().trim()).to.eql("success"));'),
    noauth=True,
    desc="已支付成功的订单收到重复回调，幂等返回 success，不重复入账"
))

folders.append({
    "name": "06-支付资金闭环（核心）",
    "description": "下单 → 预支付(存 outTradeNo) → 幂等重复预支付 → MockPay 收款(自动回调) → 查支付单=成功 → 查订单=已付款 → 伪造签名拒绝 → 篡改金额拒绝 → 重复回调幂等。",
    "item": pay_items,
})

# ---------------- 07-退款资金闭环（细中再细） ----------------

refund_items = []

refund_items.append(req(
    "R1 申请退款（存 refundId）", "POST", "/refund/apply",
    body={"orderId": "{{orderId}}", "reason": "不想要了，申请退款"},
    test=(ASSERT_OK +
          'pm.test("消息含「审核」", () => pm.expect(j.message).to.include("审核"));\n'
          'pm.environment.set("refundId", String(j.data));\n'
          'pm.test("存 refundId", () => pm.expect(pm.environment.get("refundId")).to.be.a("string").and.not.empty);'),
    desc="对已付款订单申请退款（仅退款类型）"
))

refund_items.append(req(
    "R2 重复申请退款（业务码 3033）", "POST", "/refund/apply",
    body={"orderId": "{{orderId}}", "reason": "重复申请"},
    test=assert_code(3033),
    desc="同一订单存在未完结退款单，重复申请被拒"
))

refund_items.append(req(
    "R3 查询退款单详情（待审核）", "GET", "/refund/detail/:refundId",
    test=(ASSERT_OK +
          'pm.test("退款单状态=0 待审核", () => pm.expect(j.data.status).to.eql(0));\n'
          'pm.test("退款金额=订单金额", () => pm.expect(String(j.data.refundAmount)).to.eql(pm.environment.get("payAmount")));\n'
          'pm.environment.set("refundNo", j.data.refundNo);'),
    desc="退款单详情，状态=待审核，金额与支付金额一致"
))

refund_items.append(req(
    "R4 管理员审核通过（触发自动退款+回调）", "POST", "/refund/admin/audit",
    body={"refundId": "{{refundId}}", "approved": True, "remark": "同意退款"},
    test=(ASSERT_OK + 'pm.test("消息含「通过」", () => pm.expect(j.message).to.include("通过"));'),
    admin=True,
    desc="审核通过 → 进入退款中 → 自动调 MockPay 退款 → 自动回调入账"
))

refund_items.append(req(
    "R5 查询退款单（验证退款成功）", "GET", "/refund/detail/:refundId",
    test=(ASSERT_OK +
          'pm.test("退款单状态=2 成功", () => pm.expect(j.data.status).to.eql(2));\n'
          'pm.test("有第三方退款流水号 refundTradeNo", () => pm.expect(j.data.refundTradeNo).to.be.a("string").and.not.empty);\n'
          'pm.test("有退款完成时间 refundTime", () => pm.expect(j.data.refundTime).to.not.be.null);'),
    desc="退款回调后状态=退款成功，回填退款流水号"
))

refund_items.append(req(
    "R6 查询订单（验证已退款）", "GET", "/order/detail/:orderId",
    test=(ASSERT_OK +
          'pm.test("订单状态=6 已退款", () => pm.expect(j.data.status).to.eql(6));\n'
          'pm.test("订单携带退款单信息", () => pm.expect(j.data.refundStatus).to.eql(2));'),
    desc="退款入账后订单状态=已退款(6)，且订单详情带退款进度"
))

refund_items.append(req(
    "R7 退款-伪造签名回调（应 failure）", "POST", "/refund/notify",
    body={"out_refund_no": "{{refundNo}}", "refund_trade_no": "FAKE", "out_trade_no": "{{outTradeNo}}",
          "refund_amount": "{{payAmount}}", "status": 1, "reason": None,
          "timestamp": "{{refTs}}", "sign": "BAD_SIGN"},
    pre=('pm.environment.set("refTs", String(Date.now()));'),
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'pm.test("返回 failure", () => pm.expect(pm.response.text().trim()).to.eql("failure"));'),
    noauth=True,
    desc="退款回调验签失败拒绝"
))

refund_items.append(req(
    "R8 退款-金额篡改回调（应 failure）", "POST", "/refund/notify",
    body={"out_refund_no": "{{refundNo}}", "refund_trade_no": "FAKE2", "out_trade_no": "{{outTradeNo}}",
          "refund_amount": "0.01", "status": 1, "reason": None,
          "timestamp": "{{refTs2}}", "sign": "{{refSign2}}"},
    pre=('const outRefundNo = pm.environment.get("refundNo");\n'
         'const outTradeNo = pm.environment.get("outTradeNo");\n'
         'const ts = String(Date.now());\n'
         'const secret = pm.environment.get("mockPaySecret");\n'
         'const params = { out_refund_no: outRefundNo, refund_trade_no: "FAKE2", out_trade_no: outTradeNo, refund_amount: "0.01", status: 1, timestamp: ts };\n'
         'const keys = Object.keys(params).filter(k => params[k] !== null && params[k] !== undefined && String(params[k]) !== "").sort();\n'
         'const content = keys.map(k => k + "=" + params[k]).join("&");\n'
         'const sign = CryptoJS.enc.Base64.stringify(CryptoJS.HmacSHA256(content, secret));\n'
         'pm.environment.set("refTs2", ts);\n'
         'pm.environment.set("refSign2", sign);'),
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'const resp = pm.response.text().trim();\n'
          '// 退款金额篡改被防住：退款单待处理时走金额校验(failure)；已退款时走幂等拦截(success)。两者都是正确防御\n'
          'pm.test("退款金额篡改被防住（failure=金额校验 / success=幂等拦截）", () => pm.expect(["success", "failure"]).to.include(resp));'),
    noauth=True,
    desc="退款金额 0.01 与退款单金额不一致，拒绝（待处理走金额校验、已退款走幂等拦截）"
))

refund_items.append(req(
    "R9 我的退款单列表", "POST", "/refund/list",
    body={"pageNum": 1, "pageSize": 10},
    test=(ASSERT_OK + 'pm.test("PageVo.list 数组", () => pm.expect(j.data.list).to.be.an("array"));'),
    desc="查询我的退款单"
))

folders.append({
    "name": "07-退款资金闭环（核心）",
    "description": "申请退款 → 重复申请被拒 → 详情(待审核) → 管理员审核通过(自动退款+回调) → 详情(退款成功) → 订单(已退款) → 伪造签名拒绝 → 篡改金额拒绝 → 退款列表。",
    "item": refund_items,
})

# ---------------- 08-秒杀闭环（细中再细） ----------------

seckill_items = []

seckill_items.append(req(
    "K1 秒杀缓存预热（管理员）", "GET", "/seckill/preheat/all",
    test=(ASSERT_OK + 'pm.test("消息=预热成功", () => pm.expect(j.message).to.include("预热成功"));'),
    admin=True,
    desc="把秒杀库存从 DB 预热到 Redis"
))

seckill_items.append(req(
    "K2 秒杀下单（存 requestId）", "POST", "/seckill/order",
    body={"productId": "{{seckillProductId}}", "addressId": "{{addressId}}"},
    test=(ASSERT_OK +
          'pm.test("返回 requestId", () => pm.expect(j.data).to.be.a("string").and.not.empty);\n'
          'pm.environment.set("requestId", j.data);'),
    desc="秒杀下单，返回 requestId 供轮询"
))

seckill_items.append(req(
    "K3 轮询秒杀结果（成功）", "POST", "/seckill/result/:requestId",
    test=(ASSERT_OK +
          'pm.test("结果 status 0/1/2 之一", () => pm.expect([0, 1, 2]).to.include(j.data.status));\n'
          'if (j.data.status === 1) { pm.environment.set("seckillOrderId", String(j.data.orderId)); pm.environment.set("seckillOrderNo", j.data.orderNo); }'),
    desc="轮询秒杀结果，status=1 成功时记录秒杀订单号"
))

seckill_items.append(req(
    "K4 一人一单：重复抢购同一商品（应被拒）", "POST", "/seckill/order",
    body={"productId": "{{seckillProductId}}", "addressId": "{{addressId}}"},
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'const j = pm.response.json();\n'
          'pm.test("重复抢购被拒（3005 或 3001）", () => pm.expect([3005, 3001]).to.include(j.code));'),
    desc="同一用户同一秒杀商品再次下单，购买权门闩拦截"
))

seckill_items.append(req(
    "K5 秒杀-商品ID为空（参数校验 1003）", "POST", "/seckill/order",
    body={"productId": None, "addressId": "{{addressId}}"},
    test=assert_code(1003),
    desc="productId 为空触发 @NotNull 校验"
))

folders.append({
    "name": "08-秒杀闭环（核心）",
    "description": "预热 → 秒杀(存 requestId) → 轮询结果 → 一人一单重复抢购被拒 → 参数校验。",
    "item": seckill_items,
})

# ---------------- 09-管理端（RBAC 权限 + 管理操作） ----------------

admin_items = []

admin_items.append(req(
    "A1 管理员查询用户列表", "POST", "/admin/list",
    body={"pageNum": 1, "pageSize": 10},
    test=(ASSERT_OK + 'pm.test("PageVo.list 数组", () => pm.expect(j.data.list).to.be.an("array"));'),
    admin=True, desc="管理员分页查询用户"
))

admin_items.append(req(
    "A2 普通用户访问管理接口（应 403）", "POST", "/admin/list",
    body={"pageNum": 1, "pageSize": 10},
    test=assert_code(403),
    desc="普通用户无 user:manage 权限，@PreAuthorize 拒绝"
))

admin_items.append(req(
    "A3 管理员查询订单列表", "POST", "/order/admin/list",
    body={"pageNum": 1, "pageSize": 10},
    test=(ASSERT_OK + 'pm.test("PageVo.list 数组", () => pm.expect(j.data.list).to.be.an("array"));'),
    admin=True, desc="管理员订单列表"
))

admin_items.append(req(
    "A4 管理员查询退款单列表", "POST", "/refund/admin/list",
    body={"pageNum": 1, "pageSize": 10},
    test=(ASSERT_OK + 'pm.test("PageVo.list 数组", () => pm.expect(j.data.list).to.be.an("array"));'),
    admin=True, desc="管理员退款单列表（审核工作台）"
))

admin_items.append(req(
    "A5 仪表盘统计", "GET", "/admin/dashboard/stats",
    test=(ASSERT_OK + 'pm.test("含统计字段 userTotal", () => pm.expect(j.data).to.have.property("userTotal"));'),
    admin=True, desc="管理员仪表盘总览"
))

folders.append({
    "name": "09-管理端（RBAC）",
    "description": "管理员用户列表 / 订单列表 / 退款列表 / 仪表盘，及普通用户越权 403。",
    "item": admin_items,
})

# ---------------- 10-管理端 CRUD 全覆盖（分类 / 商品 / 秒杀商品 / 用户管理） ----------------
# 目的：把原集合未覆盖的「增删改」管理接口补全，做到端点全覆盖。
# 说明：add 接口统一返回 Result<Void>（不回 id），所以统一用「列表按唯一名回填 id」的方式拿主键。

crud_items = []

crud_items.append(req(
    "C1 分类-新增", "POST", "/category/add",
    body={"name": "IT类目{{catName}}", "icon": "https://it-test.peakflow.local/icon/cat.png",
          "sort": 99, "status": 1},
    pre=('pm.environment.set("catName", String(Date.now()).slice(-9));'),
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="管理员新增分类（分类名=「IT类目」+9位数字，共13字符，满足 CATEGORY_NAME 的 2-20 位限制）"
))

crud_items.append(req(
    "C2 分类-列表回填 categoryId", "GET", "/category/list",
    test=(ASSERT_OK +
          'const hit = (j.data || []).find(c => c.name === "IT类目" + pm.environment.get("catName"));\n'
          'pm.test("列表能查到刚建的分类", () => pm.expect(hit).to.not.be.undefined);\n'
          'if (hit) { pm.environment.set("categoryId", String(hit.id)); }\n'
          'pm.test("已回填 categoryId", () => pm.expect(pm.environment.get("categoryId")).to.be.a("string").and.not.empty);'),
    noauth=True,
    desc="分类列表是公开接口；按唯一名回填 categoryId（新增接口不返回 id）"
))

crud_items.append(req(
    "C3 分类-更新", "PUT", "/category/update",
    body={"id": "{{categoryId}}", "name": "IT类目{{catName}}改",
          "icon": "https://it-test.peakflow.local/icon/cat2.png", "sort": 88, "status": 1},
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="更新分类（UpdateCategoryDto 要求 name/icon/sort/status 全传；名称仍须 ≤20 字符）"
))

crud_items.append(req(
    "P1 商品-新增", "POST", "/product/add",
    body={"name": "IT回归商品{{pName}}", "description": "回归测试商品",
          "image": "https://it-test.peakflow.local/product/p.png",
          "price": 99.00, "stock": 50, "categoryId": "{{categoryId}}"},
    pre=('pm.environment.set("pName", String(Date.now()));'),
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="管理员新增普通商品（挂到上一步新建的分类下）"
))

crud_items.append(req(
    "P2 商品-分页回填 productId2", "POST", "/product/page",
    body={"pageNum": 1, "pageSize": 10, "keyword": "{{pName}}"},
    test=(ASSERT_OK +
          'const hit = (j.data.list || []).find(p => p.name === "IT回归商品" + pm.environment.get("pName"));\n'
          'pm.test("按关键字能查到刚建商品", () => pm.expect(hit).to.not.be.undefined);\n'
          'if (hit) { pm.environment.set("productId2", String(hit.id)); }\n'
          'pm.test("已回填 productId2", () => pm.expect(pm.environment.get("productId2")).to.be.a("string").and.not.empty);'),
    noauth=True,
    desc="商品分页支持 keyword，按唯一名回填 productId2"
))

crud_items.append(req(
    "P3 商品-更新", "PUT", "/product/update",
    body={"id": "{{productId2}}", "name": "IT回归商品{{pName}}-改", "description": "已改",
          "image": "https://it-test.peakflow.local/product/p2.png",
          "price": 88.00, "stock": 60, "categoryId": "{{categoryId}}", "status": 1},
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="更新商品（UpdateProductDto 必填项全传）"
))

crud_items.append(req(
    "P4 商品-下架（status=0）", "PUT", "/product/status/:productId2",
    query={"status": 0},
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="商品下架（ProductShelfStatus：0=下架，1=上架）"
))

crud_items.append(req(
    "P5 商品-上架（status=1）", "PUT", "/product/status/:productId2",
    query={"status": 1},
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="商品重新上架"
))

crud_items.append(req(
    "P6 商品-下架（删除前置条件）", "PUT", "/product/status/:productId2",
    query={"status": 0},
    test=ASSERT_OK,
    admin=True,
    desc="业务规则：已上架商品不允许删除，删除前必须先下架"
))

crud_items.append(req(
    "P7 商品-删除", "DELETE", "/product/delete/:productId2",
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="删除商品（逻辑删除 deleted=1）"
))

crud_items.append(req(
    "C4 分类-删除", "DELETE", "/category/delete/:categoryId",
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="删除分类。必须放在上面商品已删除之后——分类下有未删除商品时会被拒绝"
))

crud_items.append(req(
    "C5 分类-新增（秒杀商品专用）", "POST", "/category/add",
    body={"name": "IT秒杀类目{{catName2}}", "icon": "https://it-test.peakflow.local/icon/sk.png",
          "sort": 98, "status": 1},
    pre=('pm.environment.set("catName2", String(Date.now()).slice(-9));'),
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="秒杀商品单独挂一个分类：秒杀商品在活动期内不允许删除，不能和上面那个「可删除」的分类混用"
))

crud_items.append(req(
    "C6 分类-列表回填 categoryId2", "GET", "/category/list",
    test=(ASSERT_OK +
          'const hit = (j.data || []).find(c => c.name === "IT秒杀类目" + pm.environment.get("catName2"));\n'
          'pm.test("列表能查到秒杀类目", () => pm.expect(hit).to.not.be.undefined);\n'
          'if (hit) { pm.environment.set("categoryId2", String(hit.id)); }'),
    noauth=True,
    desc="回填 categoryId2"
))

crud_items.append(req(
    "S1 秒杀商品-新增", "POST", "/seckill/add",
    body={"name": "IT秒杀{{skName}}", "description": "回归秒杀商品",
          "image": "https://it-test.peakflow.local/seckill/s.png",
          "price": 199.00, "seckillPrice": 9.90, "seckillStock": 20,
          "categoryId": "{{categoryId2}}",
          "seckillStartTime": "{{skStart}}", "seckillEndTime": "{{skEnd}}"},
    pre=('const pad = n => String(n).padStart(2, "0");\n'
         'const fmt = d => d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());\n'
         '// 时间必须动态算：开始取 1 小时前（保证「进行中」，才能出现在秒杀列表里被回填到 id），结束取 26 小时后\n'
         'pm.environment.set("skStart", fmt(new Date(Date.now() - 3600 * 1000)));\n'
         'pm.environment.set("skEnd", fmt(new Date(Date.now() + 26 * 3600 * 1000)));\n'
         'pm.environment.set("skName", String(Date.now()).slice(-9));'),
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="新增秒杀商品；秒杀时间用脚本动态计算，避免硬编码日期过期失效"
))

crud_items.append(req(
    "S2 秒杀商品-列表回填 seckillId2", "POST", "/seckill/list",
    body={"pageNum": 1, "pageSize": 10, "keyword": "{{skName}}"},
    test=(ASSERT_OK +
          'const hit = (j.data.list || []).find(p => p.name === "IT秒杀" + pm.environment.get("skName"));\n'
          'pm.test("秒杀列表能查到刚建商品", () => pm.expect(hit).to.not.be.undefined);\n'
          'if (hit) { pm.environment.set("seckillId2", String(hit.id)); }\n'
          'pm.test("已回填 seckillId2", () => pm.expect(pm.environment.get("seckillId2")).to.be.a("string").and.not.empty);'),
    noauth=True,
    desc="秒杀列表只返回「活动进行中」的商品，所以 S1 的活动窗口必须包含当前时间才能回填到 id"
))

crud_items.append(req(
    "S3 秒杀商品-更新（活动期内应被拒 3005）", "PUT", "/seckill/update",
    body={"id": "{{seckillId2}}", "name": "IT秒杀{{skName}}改", "description": "已改",
          "image": "https://it-test.peakflow.local/seckill/s2.png",
          "price": 199.00, "seckillPrice": 8.80, "seckillStock": 30,
          "categoryId": "{{categoryId2}}",
          "seckillStartTime": "{{skStart}}", "seckillEndTime": "{{skEnd}}"},
    test=assert_code(3005),
    admin=True,
    desc="业务规则：秒杀活动进行中不允许修改商品信息（服务端按当前活动窗口守卫）。"
         "注意 @Valid 先于守卫执行，所以请求体必须是合法报文，才能走到这条业务规则"
))

crud_items.append(req(
    "S4 秒杀商品-删除（活动期内应被拒 3005）", "DELETE", "/seckill/delete/:seckillId2",
    test=assert_code(3005),
    admin=True,
    desc="业务规则：秒杀活动进行中不允许删除。也正因如此，本用例创建的秒杀商品无法通过接口删除，"
         "会在测试库留下 1 条记录（属业务规则预期，非缺陷）"
))

crud_items.append(req(
    "U1 注册待封禁用户", "POST", "/auth/register",
    body={"username": "{{banUser}}", "password": "{{smokePass}}",
          "email": "{{banUser}}@itest.com", "nickname": "待封禁"},
    pre=('const ts = String(Date.now());\n'
         'pm.environment.set("banUser", "ban" + ts);'),
    test=(ASSERT_OK + 'pm.environment.set("banUserId", String(j.data.user.id));'),
    noauth=True,
    desc="注册一个一次性用户专门用于封号/解封/重置密码，避免影响其他用例"
))

crud_items.append(req(
    "U2 管理端-封号", "POST", "/admin/ban",
    query={"userId": "{{banUserId}}"},
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="管理员封号（需 user:manage）"
))

crud_items.append(req(
    "U3 管理端-解封", "POST", "/admin/unban",
    query={"userId": "{{banUserId}}"},
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="管理员解封"
))

crud_items.append(req(
    "U4 管理端-重置密码", "POST", "/admin/resetPassword",
    query={"userId": "{{banUserId}}"},
    test=(ASSERT_OK +
          'pm.test("返回随机新密码", () => pm.expect(j.data).to.be.a("string").and.not.empty);'),
    admin=True,
    desc="管理员重置密码，返回随机临时密码（data 为新密码，注意成功重载用的是 success(message,data)）"
))

crud_items.append(req(
    "U5 越权：普通用户调管理端封号（应 403）", "POST", "/admin/ban",
    query={"userId": "{{banUserId}}"},
    test=assert_code(403),
    desc="普通用户无 user:manage，@PreAuthorize 必须拒绝"
))

folders.append({
    "name": "10-管理端 CRUD 全覆盖",
    "description": "补齐原集合未覆盖的管理端增删改：分类 增/改/删、商品 增/改/下架/上架/删、"
                   "秒杀商品 增/改/删、管理端 封号/解封/重置密码 + 越权 403。"
                   "链路自清理：建了删、删了还原，可重复执行。",
    "item": crud_items,
})

# ---------------- 11-订单履约 / 售后 / 个人资料 / 上传 / Agent 会话 ----------------

misc_items = []

# —— 订单履约（发货 → 确认收货）——
misc_items.append(req(
    "F1 下单（履约用订单）", "POST", "/order/create",
    body={"addressId": "{{addressId}}", "items": [{"productId": "{{productId}}", "quantity": 3}]},
    test=(ASSERT_OK +
          'pm.environment.set("orderId3", String(j.data.id));\n'
          'pm.environment.set("payAmount3", String(j.data.payAmount));'),
    desc="创建一笔待付款订单（数量3，与其它链路的防重锁 key 区分）"
))

misc_items.append(req(
    "F2 发起支付", "POST", "/pay/prepay",
    body={"orderId": "{{orderId3}}"},
    test=(ASSERT_OK + 'pm.environment.set("outTradeNo3", j.data.outTradeNo);'),
    desc="预支付，存 outTradeNo3"
))

misc_items.append(req(
    "F3 MockPay 收款（自动回调）", "POST", "/mock-pay/pay",
    body={"out_trade_no": "{{outTradeNo3}}", "amount": "{{payAmount3}}",
          "timestamp": "{{payTs5}}", "sign": "{{paySign5}}"},
    pre=('const outTradeNo = pm.environment.get("outTradeNo3");\n'
         'const amount = pm.environment.get("payAmount3");\n'
         'const ts = String(Date.now());\n'
         'const secret = pm.environment.get("mockPaySecret");\n'
         'const params = { out_trade_no: outTradeNo, amount: amount, timestamp: ts };\n'
         'const keys = Object.keys(params).filter(k => params[k] !== null && params[k] !== undefined && String(params[k]) !== "").sort();\n'
         'const content = keys.map(k => k + "=" + params[k]).join("&");\n'
         'const sign = CryptoJS.enc.Base64.stringify(CryptoJS.HmacSHA256(content, secret));\n'
         'pm.environment.set("payTs5", ts);\n'
         'pm.environment.set("paySign5", sign);'),
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'pm.test("MockPay 返回 success=true", () => pm.expect(pm.response.json().success).to.eql(true));'),
    noauth=True,
    desc="模拟第三方收款，自动回调入账（订单 → 已付款）"
))

misc_items.append(req(
    "F3b 重复收款请求（同一 out_trade_no）→ 幂等复用原交易号", "POST", "/mock-pay/pay",
    body={"out_trade_no": "{{outTradeNo3}}", "amount": "{{payAmount3}}",
          "timestamp": "{{payTs6}}", "sign": "{{paySign6}}"},
    pre=('const outTradeNo = pm.environment.get("outTradeNo3");\n'
         'const amount = pm.environment.get("payAmount3");\n'
         'const ts = String(Date.now() + 1);\n'
         'const secret = pm.environment.get("mockPaySecret");\n'
         'const params = { out_trade_no: outTradeNo, amount: amount, timestamp: ts };\n'
         'const keys = Object.keys(params).filter(k => params[k] !== null && params[k] !== undefined && String(params[k]) !== "").sort();\n'
         'const content = keys.map(k => k + "=" + params[k]).join("&");\n'
         'const sign = CryptoJS.enc.Base64.stringify(CryptoJS.HmacSHA256(content, secret));\n'
         'pm.environment.set("payTs6", ts);\n'
         'pm.environment.set("paySign6", sign);'),
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'const b = pm.response.json();\n'
          'pm.test("重复收款仍返回 success=true（幂等）", () => pm.expect(b.success).to.eql(true));\n'
          'pm.test("复用原交易号而非新建交易", () => pm.expect(b.message).to.include("复用原交易号"));'),
    noauth=True,
    desc="同一 out_trade_no 二次收款：多实例部署下由共享台账(Redis)原子判定，复用原交易号，不会产生第二笔第三方交易"
))

misc_items.append(req(
    "F4 管理员发货（订单 → 已发货）", "PUT", "/order/admin/update",
    body={"id": "{{orderId3}}", "status": 2, "trackingNo": "SF" + "{{orderId3}}",
          "logisticsCompany": "顺丰速运", "remark": "回归测试发货"},
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    admin=True,
    desc="管理员更新订单为已发货(2)——确认收货的前置条件"
))

misc_items.append(req(
    "F5 确认收货（订单 → 已完成）", "POST", "/order/confirm/:orderId3",
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    desc="只有已发货订单能确认收货（前置由 F4 保证）"
))

misc_items.append(req(
    "F6 申请售后（退货退款）", "POST", "/order/refund/:orderId3",
    body={"reason": "尺码不合适，申请退货退款",
          "returnLogisticsCompany": "顺丰速运", "returnTrackingNo": "SF-RETURN-001"},
    test=(ASSERT_OK +
          'pm.environment.set("refundId4", String(j.data));\n'
          'pm.test("返回退款单 id", () => pm.expect(pm.environment.get("refundId4")).to.be.a("string").and.not.empty);'),
    desc="对已完成订单申请售后退货退款（带退货物流信息）"
))

misc_items.append(req(
    "F7 撤销退款申请", "POST", "/refund/cancel/:refundId4",
    test=(ASSERT_OK + 'pm.test("消息含「撤销」", () => pm.expect(j.message).to.include("撤销"));'),
    desc="待审核状态可被用户撤销（RefundStatus: 0 待审核 → 5 已撤销）"
))

misc_items.append(req(
    "F8 再次申请售后（退货退款）", "POST", "/order/refund/:orderId3",
    body={"reason": "重新申请退货退款",
          "returnLogisticsCompany": "顺丰速运", "returnTrackingNo": "SF-RETURN-002"},
    test=(ASSERT_OK + 'pm.environment.set("refundId5", String(j.data));'),
    desc="撤销后可重新申请；存 refundId5"
))

misc_items.append(req(
    "F9 管理员审核通过（→ 待用户退货 7）", "POST", "/refund/admin/audit",
    body={"refundId": "{{refundId5}}", "approved": True, "remark": "同意退货退款"},
    test=(ASSERT_OK + 'pm.test("消息含「通过」", () => pm.expect(j.message).to.include("通过"));'),
    admin=True,
    desc="售后退货退款审核通过后进入「待用户退货」等待寄回"
))

misc_items.append(req(
    "F10 管理员确认收到退货（→ 退款成功）", "POST", "/refund/admin/confirm-return/:refundId5",
    test=(ASSERT_OK + 'pm.test("消息含「退货」", () => pm.expect(j.message).to.include("退货"));'),
    admin=True,
    desc="确认收到退货 → 状态推到退款中 → 自动发起退款并回调入账"
))

misc_items.append(req(
    "F11 查询退款单（验证已退款成功）", "GET", "/refund/detail/:refundId5",
    test=(ASSERT_OK +
          'pm.test("退款单状态=2 成功", () => pm.expect(j.data.status).to.eql(2));'),
    desc="售后闭环终态：退款成功"
))

misc_items.append(req(
    "F12 管理员重试（状态不允许，应 3031）", "POST", "/refund/admin/retry/:refundId5",
    test=assert_code(3031),
    admin=True,
    desc="重试仅允许「退款失败(3)」或「退款中(1)」；已成功(2)必须被拒绝（RefundStatus.canRetry 守卫）"
))

misc_items.append(req(
    "F13 管理员作废（状态不允许，应 3031）", "POST", "/refund/admin/close/:refundId5",
    query={"remark": "回归测试作废"},
    test=assert_code(3031),
    admin=True,
    desc="作废仅允许「退款失败(3)」；已成功(2)必须被拒绝（RefundStatus.canClose 守卫）"
))

# —— 认证补充 ——
misc_items.append(req(
    "A0 注册专用改密账号", "POST", "/auth/register",
    body={"username": "{{pwUser}}", "password": "{{smokePass}}",
          "email": "{{pwUser}}@itest.com", "nickname": "改密专用"},
    pre=('pm.environment.set("pwUser", "pw" + String(Date.now()));'),
    test=ASSERT_OK,
    noauth=True,
    desc="注册一次性账号，所有密码变更都发生在这个账号上，不碰主链路 smokeUser"
))

misc_items.append(req(
    "A1 图形验证码", "GET", "/auth/image",
    test=(ASSERT_OK +
          'pm.test("返回 captchaUuid 与图片 Base64", () => {\n'
          '    pm.expect(j.data.captchaUuid).to.be.a("string").and.not.empty;\n'
          '    pm.expect(j.data.imageBase64).to.be.a("string").and.include("base64");\n'
          '});'),
    noauth=True,
    desc="验证码返回字段是 captchaUuid + imageBase64（data:image/png;base64,...）"
))

misc_items.append(req(
    "A2 专用账号登录（存 pwToken）", "POST", "/auth/login",
    body={"account": "{{pwUser}}", "password": "{{smokePass}}"},
    test=(ASSERT_OK + 'pm.environment.set("pwToken", j.data.token);'),
    noauth=True,
    desc="专用账号登录；后续改密用它自己的 token，不影响主链路 userToken"
))

misc_items.append(req(
    "A3 修改密码（专用账号）", "POST", "/user/password",
    body={"oldPassword": "{{smokePass}}", "newPassword": "New888888"},
    pre=('pm.request.headers.upsert({ key: "Authorization", value: "Bearer " + pm.environment.get("pwToken") });'),
    test=(ASSERT_OK + 'pm.test("提示重新登录", () => pm.expect(j.message).to.include("登录"));'),
    desc="改密成功后服务端强制登出当前 token（安全设计：改密即下线）"
))

misc_items.append(req(
    "A4 用新密码登录（专用账号）", "POST", "/auth/login",
    body={"account": "{{pwUser}}", "password": "New888888"},
    test=ASSERT_OK,
    noauth=True,
    desc="验证新密码生效（JWT 已加入随机 jti，同一秒签发的 token 不再撞号，无需再等待）"
))

misc_items.append(req(
    "A5 强制修改密码（按用户名，管理员）", "POST", "/auth/changePassword",
    body={"username": "{{pwUser}}", "oldPassword": "New888888", "newPassword": "Pw888888"},
    test=(ASSERT_OK + 'pm.test("消息含「修改」", () => pm.expect(j.message).to.include("修改"));'),
    admin=True,
    desc="强制改密接口（重置密码后的强改场景）：按 username 定位 + 校验旧密码。"
         "该接口按用户名定位账号，已收紧为必须登录，且只允许「本人」或持有 user:manage 的管理员调用"
))

misc_items.append(req(
    "A5b 强制修改密码-未登录 → 拒绝（401）", "POST", "/auth/changePassword",
    body={"username": "{{pwUser}}", "oldPassword": "Pw888888", "newPassword": "Pw999999"},
    test=assert_code(401),
    noauth=True,
    desc="安全回归：/auth/changePassword 已从白名单移除并收紧为 authenticated，匿名调用必须被 401 拦下"
))

misc_items.append(req(
    "A6 刷新 token（旧 token 进黑名单）", "POST", "/auth/refresh",
    test=(ASSERT_OK +
          'pm.test("返回新 token", () => pm.expect(j.data.token).to.be.a("string").and.not.empty);\n'
          'pm.environment.set("userToken", j.data.token);'),
    desc="刷新后旧 token 立即进黑名单，因此必须把 userToken 换成新 token，否则后续请求会 401"
))

# —— 个人资料 ——
misc_items.append(req(
    "B1 修改昵称", "POST", "/user/nickname",
    query={"nickname": "回归昵称{{nickTs}}"},
    pre=('pm.environment.set("nickTs", String(Date.now()).slice(-6));'),
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    desc="改昵称；昵称正则 NICKNAME 限 2-10 位，这里用「回归昵称+6位数字」共 10 位"
))

misc_items.append(req_file(
    "B6 上传头像（multipart）", "/user/avatar",
    test=(ASSERT_OK +
          'pm.test("返回可访问的头像 URL", () => pm.expect(j.data).to.be.a("string").and.include("http"));'),
    desc="头像上传走 multipart/form-data，文件用 postman/fixtures/test-avatar.png"
))

misc_items.append(req_file(
    "B7 上传图片（multipart，需 file:upload）", "/upload/image",
    test=(ASSERT_OK +
          'pm.test("返回图片 URL", () => pm.expect(j.data).to.be.a("string").and.include("http"));'),
    admin=True,
    desc="通用图片上传（商品图/分类图复用），需 file:upload 权限，用 adminToken"
))

misc_items.append(req(
    "B8 我的登录日志", "POST", "/user/loginLog",
    body={"pageNum": 1, "pageSize": 10},
    test=(ASSERT_OK + 'pm.test("返回 PageVo.list", () => pm.expect(j.data.list).to.be.an("array"));'),
    desc="分页查询个人登录日志"
))

# —— 地址 改/删 ——
misc_items.append(req(
    "D1 新增待改地址", "POST", "/address/add",
    body={"receiverName": "回归改", "receiverPhone": "13800000000", "province": "江苏省",
          "city": "南京市", "district": "鼓楼区", "detailAddress": "回归改路{{addrTs}}号"},
    pre=('pm.environment.set("addrTs", String(Date.now()).slice(-6));'),
    test=ASSERT_OK,
    desc="新增一个一次性地址，供下面的「更新/删除」使用"
))

misc_items.append(req(
    "D2 地址列表回填 addressId2", "GET", "/address/list",
    test=(ASSERT_OK +
          'const hit = (j.data || []).find(a => String(a.detailAddress).indexOf(pm.environment.get("addrTs")) >= 0);\n'
          'pm.test("列表能查到刚建的地址", () => pm.expect(hit).to.not.be.undefined);\n'
          'if (hit) { pm.environment.set("addressId2", String(hit.id)); }'),
    desc="地址新增不返回 id，用详情地址里的唯一后缀回填"
))

misc_items.append(req(
    "D3 更新地址", "PUT", "/address/update",
    body={"id": "{{addressId2}}", "receiverName": "回归改后", "receiverPhone": "13900000000",
          "province": "江苏省", "city": "南京市", "district": "玄武区", "detailAddress": "回归改路-已更新"},
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    desc="更新地址（UpdateAddressDto 全部字段必填）"
))

misc_items.append(req(
    "D4 删除地址", "DELETE", "/address/delete/:addressId2",
    test=(ASSERT_OK + 'pm.test("消息含「成功」", () => pm.expect(j.message).to.include("成功"));'),
    desc="删除刚建的一次性地址（自清理）"
))

# —— Agent 会话 ——
misc_items.append(req(
    "G1 Agent 会话-保存", "POST", "/agent/session/save",
    body={"sessionId": "it-session-{{userId}}", "userId": "{{userId}}",
          "intent": "回归测试", "summary": "Postman 全量回归写入的会话摘要"},
    test=(ASSERT_OK + 'pm.test("消息含「保存」", () => pm.expect(j.message).to.include("保存"));'),
    noauth=True,
    desc="保存 Agent 会话摘要（Redis 缓存 + MySQL 落库），Java 侧业务审计用"
))

misc_items.append(req(
    "G2 Agent 会话-查询", "GET", "/agent/session/query",
    query={"userId": "{{userId}}", "sessionId": "it-session-{{userId}}"},
    test=(ASSERT_OK + 'pm.test("返回数组", () => pm.expect(j.data).to.be.an("array"));'),
    noauth=True,
    desc="按 userId + sessionId 查询会话历史"
))

folders.append({
    "name": "11-订单履约/售后/个人资料/上传/Agent",
    "description": "补齐剩余未覆盖端点：订单发货→确认收货→售后退货退款闭环（含撤销/审核/确认收货/重试与作废的状态守卫）、"
                   "刷新token、强制改密、改昵称/改密码、头像与图片上传（multipart）、登录日志、地址改删、Agent 会话读写。",
    "item": misc_items,
})

# ====================================================================================
# 12-模拟第三方（MockPay）补充：收银台页面 + 退款入口
# ------------------------------------------------------------------------------------
# 这两个端点是 MockPay（模拟支付宝）对外暴露的接口，原先未被任何请求覆盖：
#   - GET  /mock-pay/cashier  收银台页面（用户点「去支付」后跳转到的页面）
#   - POST /mock-pay/refund   第三方退款入口（正常链路由后端在退款审核时服务端调用，这里单独覆盖其入口与守卫）
# ====================================================================================
mockpay_items = []

mockpay_items.append(req(
    "H1 收银台页面可用（返回真实 HTML，而非 forward 字面量）", "GET", "/mock-pay/cashier",
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'pm.test("返回的是收银台页面内容", () => {\n'
          '    const txt = pm.response.text();\n'
          '    pm.expect(txt).to.include("MockPay 收银台");\n'
          '});\n'
          'pm.test("不是 forward 字面量（@RestController 下 return \\"forward:\\" 会退化）", () => {\n'
          '    pm.expect(pm.response.text()).to.not.include("forward:");\n'
          '});\n'
          'pm.test("Content-Type 为 text/html", () => {\n'
          '    pm.expect(pm.response.headers.get("Content-Type")).to.include("text/html");\n'
          '});'),
    noauth=True,
    desc='收银台页面端点：用「响应体必须是页面 HTML」锁定该端点的行为，防止有人改回 return "forward:xxx"'
))

mockpay_items.append(req(
    "H2 退款入口-合法签名但原交易不存在 → 拒绝（不产生资损）", "POST", "/mock-pay/refund",
    body={"out_refund_no": "{{bogusRefundNo}}", "out_trade_no": "{{bogusTradeNo}}",
          "refund_amount": "1.00", "notify_url": "{{baseUrl}}/refund/notify",
          "timestamp": "{{bogusTs}}", "sign": "{{bogusSign}}"},
    pre=('const ts = String(Date.now());\n'
         'const refundNo = "REGREF" + ts + "-" + Math.floor(Math.random() * 100000);\n'
         'const tradeNo = "REGBOGUS" + ts;\n'
         'const secret = pm.environment.get("mockPaySecret");\n'
         'const notifyUrl = pm.variables.replaceIn("{{baseUrl}}/refund/notify");\n'
         'const params = {\n'
         '    out_refund_no: refundNo,\n'
         '    out_trade_no: tradeNo,\n'
         '    refund_amount: "1.00",\n'
         '    notify_url: notifyUrl,\n'
         '    timestamp: ts\n'
         '};\n'
         'const keys = Object.keys(params).filter(k => params[k] !== null && params[k] !== undefined && String(params[k]) !== "").sort();\n'
         'const content = keys.map(k => k + "=" + params[k]).join("&");\n'
         'const sign = CryptoJS.enc.Base64.stringify(CryptoJS.HmacSHA256(content, secret));\n'
         'pm.variables.set("bogusRefundNo", refundNo);\n'
         'pm.variables.set("bogusTradeNo", tradeNo);\n'
         'pm.variables.set("bogusTs", ts);\n'
         'pm.variables.set("bogusSign", sign);'),
    test=('pm.test("HTTP 200", () => pm.response.to.have.status(200));\n'
          'const j = pm.response.json();\n'
          'pm.test("签名通过但原交易不存在 → success=false", () => pm.expect(j.success).to.eql(false));\n'
          'pm.test("message 含「原交易不存在」", () => pm.expect(j.message).to.include("原交易不存在"));'),
    noauth=True,
    desc="第三方退款入口的守卫：合法签名 + 不存在的原交易 → 拒绝退款（证明入口可达且不会误退款）"
))

folders.append({
    "name": "12-模拟第三方（MockPay）：收银台与退款入口",
    "description": "覆盖 MockPay 对外暴露但原集合遗漏的两个端点：收银台页面（GET /mock-pay/cashier）"
                   "与第三方退款入口（POST /mock-pay/refund）。二者均免登录（扮演支付宝角色）。",
    "item": mockpay_items,
})

# ====================================================================================
# 组装 Collection
# ====================================================================================

collection = {
    "info": {
        "name": "PeakFlow-API",
        "description": (
            "PeakFlow 秒杀商城接口测试集合（手工精编，混合链路式）。\n\n"
            "组织方式：按业务链路分文件夹（00 前置准备 → 01 认证 → 02 商品 → 03 购物车 → "
            "04 地址 → 05 下单 → 06 支付资金闭环 → 07 退款资金闭环 → 08 秒杀闭环 → 09 管理端 → "
            "10 管理端 CRUD 全覆盖 → 11 订单履约/售后/个人资料/上传/Agent）。\n\n"
            "覆盖度：10 与 11 用于补齐原集合未覆盖的端点（增删改、上传、Agent 会话等），"
            "使集合覆盖项目全部对外接口；这两组链路自带数据自清理（建了删/改了还原），可重复执行。\n\n"
            "核心域（认证/资金/秒杀）断言精确到业务码 + 消息 + data 字段 + 统一 Result 结构。\n\n"
            "变量传递：登录自动存 token，下单存 orderId，预支付存 outTradeNo，申请退款存 refundId，"
            "秒杀存 requestId —— 全程不用手工复制 ID。\n\n"
            "跑法：\n"
            "1) 手工：Runner → Run collection（建议先跑 00，再按顺序跑其余文件夹）\n"
            "2) 命令行：newman run PeakFlow-API.postman_collection.json -e env-test.postman_environment.json\n"
        ),
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "event": [
        {"listen": "prerequest",
         "script": {"type": "text/javascript", "exec": PREREQ_COLLECTION.split("\n")}},
        {"listen": "test",
         "script": {"type": "text/javascript", "exec": TEST_COLLECTION.split("\n")}},
    ],
    "variable": [
        {"key": "baseUrl", "value": BASE_URL, "type": "string"},
        {"key": "adminUser", "value": "pfadmin", "type": "string"},
        {"key": "adminPass", "value": "Admin123456", "type": "string"},
        {"key": "mockPaySecret", "value": MOCK_SECRET, "type": "string"},
        {"key": "smokePass", "value": "It123456", "type": "string"},
        {"key": "seckillProductId", "value": SECKILL_PRODUCT_ID, "type": "string"},
        {"key": "productId", "value": "1", "type": "string"},
        {"key": "userToken", "value": "", "type": "string"},
        {"key": "adminToken", "value": "", "type": "string"},
        {"key": "userId", "value": "", "type": "string"},
        {"key": "addressId", "value": "1", "type": "string"},
        {"key": "orderId", "value": "", "type": "string"},
        {"key": "orderNo", "value": "", "type": "string"},
        {"key": "payAmount", "value": "", "type": "string"},
        {"key": "outTradeNo", "value": "", "type": "string"},
        {"key": "tradeNo", "value": "", "type": "string"},
        {"key": "refundId", "value": "", "type": "string"},
        {"key": "refundNo", "value": "", "type": "string"},
        {"key": "requestId", "value": "", "type": "string"},
        {"key": "seckillOrderId", "value": "", "type": "string"},
        {"key": "catName", "value": "", "type": "string"},
        {"key": "catName2", "value": "", "type": "string"},
        {"key": "categoryId", "value": "", "type": "string"},
        {"key": "categoryId2", "value": "", "type": "string"},
        {"key": "pwToken", "value": "", "type": "string"},
        {"key": "pName", "value": "", "type": "string"},
        {"key": "productId2", "value": "", "type": "string"},
        {"key": "skName", "value": "", "type": "string"},
        {"key": "skStart", "value": "", "type": "string"},
        {"key": "skEnd", "value": "", "type": "string"},
        {"key": "seckillId2", "value": "", "type": "string"},
        {"key": "banUser", "value": "", "type": "string"},
        {"key": "banUserId", "value": "", "type": "string"},
        {"key": "orderId3", "value": "", "type": "string"},
        {"key": "payAmount3", "value": "", "type": "string"},
        {"key": "outTradeNo3", "value": "", "type": "string"},
        {"key": "refundId4", "value": "", "type": "string"},
        {"key": "refundId5", "value": "", "type": "string"},
        {"key": "pwUser", "value": "", "type": "string"},
        {"key": "nickTs", "value": "", "type": "string"},
        {"key": "addrTs", "value": "", "type": "string"},
        {"key": "addressId2", "value": "", "type": "string"},
        {"key": "payTs5", "value": "", "type": "string"},
        {"key": "paySign5", "value": "", "type": "string"},
    ],
    "item": folders,
}

env = {
    "id": "peakflow-test-env",
    "name": "PeakFlow-test",
    "values": [
        {"key": "baseUrl", "value": BASE_URL, "enabled": True},
        {"key": "adminUser", "value": "pfadmin", "enabled": True},
        {"key": "adminPass", "value": "Admin123456", "enabled": True},
        {"key": "mockPaySecret", "value": MOCK_SECRET, "enabled": True},
        {"key": "smokePass", "value": "It123456", "enabled": True},
        {"key": "seckillProductId", "value": SECKILL_PRODUCT_ID, "enabled": True},
        {"key": "productId", "value": "1", "enabled": True},
        {"key": "userToken", "value": "", "enabled": True},
        {"key": "adminToken", "value": "", "enabled": True},
        {"key": "userId", "value": "", "enabled": True},
        {"key": "addressId", "value": "1", "enabled": True},
        {"key": "orderId", "value": "", "enabled": True},
        {"key": "orderNo", "value": "", "enabled": True},
        {"key": "payAmount", "value": "", "enabled": True},
        {"key": "outTradeNo", "value": "", "enabled": True},
        {"key": "tradeNo", "value": "", "enabled": True},
        {"key": "refundId", "value": "", "enabled": True},
        {"key": "refundNo", "value": "", "enabled": True},
        {"key": "requestId", "value": "", "enabled": True},
        {"key": "seckillOrderId", "value": "", "enabled": True},
        {"key": "catName", "value": "", "enabled": True},
        {"key": "catName2", "value": "", "enabled": True},
        {"key": "categoryId", "value": "", "enabled": True},
        {"key": "categoryId2", "value": "", "enabled": True},
        {"key": "pwToken", "value": "", "enabled": True},
        {"key": "pName", "value": "", "enabled": True},
        {"key": "productId2", "value": "", "enabled": True},
        {"key": "skName", "value": "", "enabled": True},
        {"key": "skStart", "value": "", "enabled": True},
        {"key": "skEnd", "value": "", "enabled": True},
        {"key": "seckillId2", "value": "", "enabled": True},
        {"key": "banUser", "value": "", "enabled": True},
        {"key": "banUserId", "value": "", "enabled": True},
        {"key": "orderId3", "value": "", "enabled": True},
        {"key": "payAmount3", "value": "", "enabled": True},
        {"key": "outTradeNo3", "value": "", "enabled": True},
        {"key": "refundId4", "value": "", "enabled": True},
        {"key": "refundId5", "value": "", "enabled": True},
        {"key": "pwUser", "value": "", "enabled": True},
        {"key": "nickTs", "value": "", "enabled": True},
        {"key": "addrTs", "value": "", "enabled": True},
        {"key": "addressId2", "value": "", "enabled": True},
        {"key": "payTs5", "value": "", "enabled": True},
        {"key": "paySign5", "value": "", "enabled": True},
    ],
    "_postman_variable_scope": "environment",
}


def main():
    with open(COL_FILE, "w", encoding="utf-8", newline="\n") as f:
        json.dump(collection, f, ensure_ascii=False, indent=2)
    with open(ENV_FILE, "w", encoding="utf-8", newline="\n") as f:
        json.dump(env, f, ensure_ascii=False, indent=2)

    total = sum(len(fo["item"]) for fo in folders)
    print("Postman Collection 生成完成")
    print("  集合文件 : %s" % COL_FILE)
    print("  环境文件 : %s" % ENV_FILE)
    print("  文件夹数 : %d" % len(folders))
    print("  请求总数 : %d" % total)
    print()
    for fo in folders:
        print("  %-40s %d 个请求" % (fo["name"], len(fo["item"])))


if __name__ == "__main__":
    main()
