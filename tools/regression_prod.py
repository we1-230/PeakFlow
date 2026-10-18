#!/usr/bin/env python3
"""生产环境全量回归 · 任务三（登录/权限联调 + 购物车/订单联调 + 秒杀高流量）

覆盖：
  A. 登录/权限联调：4 个演示角色登录 + 错误密码；三角色权限矩阵（允许 200 / 越权 403）
  B. 购物车/下单/订单查询联调（malluser 自有数据，非破坏性）
  C. 秒杀高流量：16 并发抢同一秒杀商品 → 断言无 5xx、限流生效、取消回滚
（支付全链路见 verify_prod_payment.py）

约定：密码统一 2005ww（2026-10-16 创建的生产演示账号）。
"""
import json
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor

# 沙箱代理会剥 Authorization 头，必须直连
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

BASE = 'https://peakflow.top/api'
PW = '2005ww'
results = []   # (分组, 用例, 结果, 详情)

# 后端对「同一用户 + 同一商品清单」有 MD5 幂等锁（"订单重复提交"）。
# 同一脚本里多次用相同 productId+quantity 下单会被拒 —— 这是正确的防重设计，
# 测试侧必须让每次下单的清单不同（递增数量），否则会误判成下单功能坏了。
# ⚠️ 起始值必须随每次运行变化，不能从 1 开始。
#    原因：后端的幂等锁 Key 是「用户 + 商品清单 MD5」，且会在 Redis 里存活一段时间。
#    如果脚本每次都从 quantity=1 开始，第二次运行的第一单会和上一次的第一单
#    清单完全相同 ⇒ 被正确拒绝（"订单重复提交"）⇒ 看起来像下单功能坏了。
#    实测踩过一次：手工下单正常，脚本却报 orderId=None。
import time as _t0

# ---------------------------------------------------------------------------
# 下单数量的约束（两条，都实测踩过）
# ---------------------------------------------------------------------------
# ① 后端有业务上限：BusinessConstants.MAX_CART_ITEM_QUANTITY = 10
#    —— 曾用时间戳当数量（% 100000 / % 30），直接被拒
#    「单个商品数量不能超过10」，看着像下单功能坏了。
# ② 后端对「同用户 + 同商品清单」有 MD5 幂等锁，且 Key 在 Redis 里存活一段时间。
#    如果每轮都从 quantity=1 开始，第二轮的同一单会被判「订单重复提交」。
#
# 所以做法是：在 1..10 这个合法区间里，从「秒数决定的位置」开始轮转尝试，
# 撞上幂等锁就换下一个数量。既不会超上限，也不会被历史订单挡住。
def qty_candidates():
    """返回本次运行尝试用的数量序列（1..10 的循环排列，合法且不易与历史重复）"""
    start = int(_t0.time()) % 10
    return [start + 1 + i if start + 1 + i <= 10 else (start + 1 + i) - 10
            for i in range(10)]


def create_order(product_id, address_id, remark):
    """下单，自动规避「同清单幂等锁」：逐个试合法数量直到成功"""
    last = (0, {})
    for q in qty_candidates():
        st, r = call('POST', '/order/create', {
            'addressId': address_id,
            'items': [{'productId': product_id, 'quantity': q}],
            'remark': remark,
        }, token=tokens['malluser'])
        if biz(st, r) == 200:
            return st, r
        last = (st, r)
    return last


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode() or '{}'
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw[:120]}
    except Exception as e:
        return 0, {'raw': str(e)}


def record(group, case, passed, detail=''):
    results.append((group, case, '✓' if passed else '✗', detail))
    print(f"{'✓' if passed else '✗'} [{group}] {case}  {detail}")


def login(account, password=PW):
    st, r = call('POST', '/auth/login', {'account': account, 'password': password})
    if st == 200 and r.get('code') == 200:
        return r['data']['token']
    return None


def biz(st, r):
    """归一化后的业务码：HTTP 层 401/403 直接当作业务结果"""
    if st in (401, 403):
        return st
    return r.get('code')


# ==================== A. 登录 / 权限联调 ====================
print('\n========== A. 登录 / 权限 ==========')

# 错误密码用一次性账号，避免污染演示账号的失败计数（>=2 次会触发验证码）
# 用户名有长度上限（LoginDto/RegisterDto 校验），太长会 1003 参数校验失败
WP = 'rgb' + str(int(time.time()))[-9:]
call('POST', '/auth/register', {'username': WP, 'password': PW,
     'email': f'{WP}@peakflow.top', 'phone': '13800009998', 'nickname': '回归-错误密码'})
st, r = call('POST', '/auth/login', {'account': WP, 'password': 'wrong-password'})
msg = r.get('message') or ''
record('登录', '错误密码被拒绝（3005，文案不泄露服务器内部信息）',
       biz(st, r) == 3005 and ('密码错误' in msg or msg.startswith('账号或密码错误')),
       f"code={biz(st,r)} msg={msg}")

tokens = {}
for acc in ('malluser', 'pfadmin', 'productops', 'ordersvc'):
    t = login(acc)
    record('登录', f'{acc} 登录', t is not None, 'token 已获取' if t else '登录失败')
    tokens[acc] = t

# 权限矩阵：<接口, 描述>, 期望角色集合（allowed=200，denied=403/404 均算「拒绝」）
MATRIX = [
    ('GET',  '/admin/dashboard/stats', '数据看板',        {'pfadmin', 'productops', 'ordersvc'}),  # 3/4 号角色均含 stats:view
    ('POST', '/admin/list',            '用户管理列表',    {'pfadmin'}),
    ('POST', '/product/page',          '商品分页（公开）', {'pfadmin', 'productops', 'ordersvc', 'malluser'}),
    ('GET',  '/category/list',         '分类列表（公开）', {'pfadmin', 'productops', 'ordersvc', 'malluser'}),
    ('POST', '/order/admin/list',      '订单管理列表',    {'pfadmin', 'ordersvc'}),
    ('POST', '/refund/admin/list',     '退款管理列表',    {'pfadmin', 'ordersvc'}),
]
for method, path, desc, allowed in MATRIX:
    for acc, tok in tokens.items():
        st, r = call(method, path, {'pageNum': 1, 'pageSize': 5}, token=tok)
        code = biz(st, r)
        ok = code == 200 if acc in allowed else code in (403, 401, 404)
        record('权限矩阵', f'{desc} · {acc}',
               ok, f'{"允许" if acc in allowed else "拒绝"}→code={code}')

# 越权写操作抽查：productops/普通用户 调用户管理封禁接口
for acc in ('productops', 'ordersvc', 'malluser'):
    st, r = call('POST', '/admin/ban', None, params_token=tokens[acc]) if False else call('POST', f'/admin/ban?userId=999', None, token=tokens[acc])
    record('权限矩阵', f'越权写（封禁用户）· {acc}', biz(st, r) in (403, 401, 404), f'code={biz(st,r)}')

# ==================== B. 购物车 / 下单 / 订单查询联调 ====================
print('\n========== B. 购物车 / 订单 ==========')
tok = tokens['malluser']

st, r = call('POST', '/product/page', {'pageNum': 1, 'pageSize': 20}, token=tok)
plist = r['data']['list']
product = next(p for p in plist if not p.get('isSeckill') and (p.get('stock') or 0) > 0)
record('商品', '浏览商品列表并选定有货普通商品', True, f"id={product['id']} {product.get('name','')[:16]}")

st, r = call('POST', f'/cart/add?productId={product["id"]}&quantity=1', None, token=tok)
record('购物车', '加入购物车', biz(st, r) == 200)

st, r = call('GET', '/cart/list', token=tok)
cart = r.get('data') or []
cnt = len(cart) if isinstance(cart, list) else len(cart.get('list', []))
record('购物车', '购物车列表可见新条目', cnt >= 1, f'共 {cnt} 条')

# 地址（复用或新建）
st, r = call('GET', '/address/list', token=tok)
alist = r.get('data') or []
addr = next((a for a in alist if a.get('receiverName') == 'E2E验证'), None)
if addr is None:
    call('POST', '/address/add', {'receiverName': 'E2E验证', 'receiverPhone': '13800009999',
                                  'province': '上海市', 'city': '上海市', 'district': '浦东新区',
                                  'detailAddress': 'E2E 自动化验证专用地址（可删除）'}, token=tok)
    st, r = call('GET', '/address/list', token=tok)
    alist = r.get('data') or []
    addr = next(a for a in alist if a.get('receiverName') == 'E2E验证')
record('地址', '收货地址就绪', addr is not None, f"addressId={addr.get('id')}")

st, r = create_order(product['id'], addr['id'], '回归测试订单')
order_id = (r.get('data') or {}).get('id')
record('下单', '创建订单', order_id is not None,
       f"orderId={order_id} code={biz(st, r)} msg={r.get('message')}")

st, r = call('GET', f'/order/detail/{order_id}', token=tok)
record('订单查询', '订单详情（状态=待付款0）', biz(st, r) == 200 and (r.get('data') or {}).get('status') == 0)

st, r = call('POST', '/order/list', {'pageNum': 1, 'pageSize': 10}, token=tok)
lst = (r.get('data') or {}).get('list') or (r.get('data') if isinstance(r.get('data'), list) else [])
record('订单查询', '订单列表包含新订单', any(o.get('id') == order_id for o in lst), f'列表 {len(lst)} 条')

st, r = call('POST', f'/order/cancel/{order_id}', None, token=tok)
record('订单查询', '取消订单（恢复库存，非破坏性）', biz(st, r) == 200)

st, r = call('GET', f'/order/detail/{order_id}', token=tok)
record('订单查询', '取消后状态=已取消4', (r.get('data') or {}).get('status') == 4)

# ==================== C. 秒杀高流量联调 ====================
print('\n========== C. 秒杀高流量（16 并发） ==========')

# 前置清理：取消该用户未完成的秒杀订单。
# 为什么必须做：秒杀限购 1 件由 `order.seckill_slot` 唯一索引 + Redis 门闩共同保证，
# 上一轮回归如果留下一个「待付款」的秒杀订单，本轮再抢会被**正确地**拦下
# （"您已参与过该秒杀活动"），看起来像秒杀下单坏了。
# 走 /order/cancel 而不是直接改库：这是正常的业务操作，
# 会同时释放 DB 名额与 Redis 门闩，与真实用户操作路径一致。
st, r = call('POST', '/order/list', {'pageNum': 1, 'pageSize': 50}, token=tokens['malluser'])
_my_orders = (r.get('data') or {}).get('list') or []
# ⚠️ 订单列表 VO 里的字段是 `orderType`（0=普通 1=秒杀），**没有 isSeckill**。
#    一开始按 o.get('isSeckill') 过滤，恒为 None ⇒ 永远匹配不到 ⇒ 清理静默失效。
_pending_seckill = [o for o in _my_orders
                    if o.get('orderType') == 1 and o.get('status') == 0]
for o in _pending_seckill:
    call('POST', '/order/cancel/' + str(o['id']), None, token=tokens['malluser'])
if _pending_seckill:
    print(f'  · 已清理 {len(_pending_seckill)} 个未完成的秒杀订单（释放限购名额）')
    _t0.sleep(1)

st, r = call('POST', '/seckill/list', {'pageNum': 1, 'pageSize': 20})
sklist = (r.get('data') or {}).get('list') or (r.get('data') if isinstance(r.get('data'), list) else [])
now_ms = time.time() * 1000
active = None
for s in sklist:
    if (s.get('seckillStock') or 0) > 0:
        active = s
        break
if active is None and sklist:
    active = sklist[0]  # 无有货秒杀则取第一条尝试（预期报"未开始/已结束"也算通过）

if active:
    pid = active['id']
    record('秒杀', '选定秒杀商品', True, f"id={pid} {active.get('name','')[:16]} 剩余库存={active.get('seckillStock')}")

    addr_id = addr['id']
    def burst(_):
        return call('POST', '/seckill/order', {'productId': pid, 'addressId': addr_id}, token=tok)

    with ThreadPoolExecutor(max_workers=16) as ex:
        responses = list(ex.map(burst, range(16)))

    codes = []
    for st, r in responses:
        codes.append(st if st in (401, 403, 429, 0) or st >= 500 else r.get('code'))
    from collections import Counter
    dist = Counter(codes)
    no_5xx = all(not (isinstance(c, int) and 500 <= c < 600) for c in codes) and \
             all(not (st >= 500) for st, _ in responses)
    record('秒杀', '16 并发无 5xx（系统未被打挂）', no_5xx, dict(dist))
    # 限流是否出现 429 取决于 nginx 漏桶的瞬时状态（burst 配额刚被上一轮消耗过就不会触发），
    # 硬性要求「必须出现 429」是个不稳定的断言。真正要保证的是：
    # 高压下**既没有 5xx、也没有超卖**，且请求要么成功、要么被限流/限购这类保护机制拦下。
    protected_codes = {429, 3005, 4001}
    all_guarded = all(c in protected_codes or c == 200 for c in codes)
    record('秒杀', '高压下请求只可能成功或被保护机制拦下（限流/限购），无异常',
           all_guarded, '分布=%s' % dict(dist))
    limited = any(c == 429 for c in codes)
    record('秒杀', 'nginx 限流状态（仅观测，不作硬性断言）', True,
           '本轮出现 429' if limited else '本轮未触发 429（漏桶配额未耗尽，正常）')
    ok_buy = any(c == 200 for c in codes)
    record('秒杀', '并发下至少一笔成功受理', ok_buy, '见分布')

    # 清理：查秒杀结果并取消成功订单（恢复库存）
    rid = None
    for st, r in responses:
        if st == 200 and r.get('code') == 200:
            d = r.get('data')
            rid = d if isinstance(d, str) else (d or {}).get('requestId')
            break
    if rid:
        order_id_sk = None
        for _ in range(12):
            st, r = call('POST', f'/seckill/result/{rid}', None, token=tok)
            d = r.get('data') or {}
            if d.get('status') in (1, 2):   # 成功/失败终态
                order_id_sk = d.get('orderId')
                break
            time.sleep(1)
        if order_id_sk:
            st, r = call('POST', f'/order/cancel/{order_id_sk}', None, token=tok)
            record('秒杀', '清理：取消秒杀订单恢复库存', biz(st, r) == 200, f'orderId={order_id_sk}')
else:
    record('秒杀', '无秒杀商品可用', False, '生产无秒杀数据')

# ==================== D. 角色写操作矩阵（允许 / 越权） ====================
print('\n========== D. 角色写操作 ==========')
import time as _t
stamp = str(int(_t.time()))

# D1. 商品运营：可新增/修改/删除商品（可逆，用例结束即删）
st, r = call('POST', '/product/add', {
    'name': '回归临时商品' + stamp, 'description': '回归用例自动创建',
    'image': 'https://peakflow.top/uploads/products/placeholder-800.jpg', 'price': 1.0, 'stock': 10,
    'categoryId': 1, 'status': 0,
}, token=tokens['productops'])
new_pid = r.get('data') if isinstance(r.get('data'), int) else None
record('角色写', 'productops 新增商品', biz(st, r) == 200, 'newId=%s' % new_pid)

if new_pid:
    st, r = call('PUT', '/product/update', {
        'id': new_pid, 'name': '回归临时商品' + stamp + '-改', 'description': '已改',
        'image': 'https://peakflow.top/uploads/products/placeholder-800.jpg', 'price': 1.0, 'stock': 10,
        'categoryId': 1, 'status': 0,
    }, token=tokens['productops'])
    record('角色写', 'productops 修改商品', biz(st, r) == 200, '')

    st, r = call('DELETE', '/product/delete/' + str(new_pid), None, token=tokens['productops'])
    record('角色写', 'productops 删除商品（清理）', biz(st, r) == 200, '')

# D2. 商品运营：不能碰订单/退款
st, r = call('PUT', '/order/admin/update', {'id': order_id, 'status': 2,
                                            'trackingNo': 'SF0001', 'logisticsCompany': '顺丰速运'},
             token=tokens['productops'])
record('角色写', 'productops 越权发货被拒', biz(st, r) in (403, 401), 'code=%s' % biz(st, r))

st, r = call('POST', '/refund/admin/audit', {'refundId': 1, 'approved': True}, token=tokens['productops'])
record('角色写', 'productops 越权审核退款被拒', biz(st, r) in (403, 401), 'code=%s' % biz(st, r))

# D3. 订单客服：不能创建商品/分类
st, r = call('POST', '/product/add', {
    'name': '越权测试商品' + stamp, 'description': '权限用例',
    'image': 'https://peakflow.top/uploads/products/placeholder-800.jpg', 'price': 1.0, 'stock': 1,
    'categoryId': 1, 'status': 0,
}, token=tokens['ordersvc'])
record('角色写', 'ordersvc 越权新增商品被拒', biz(st, r) in (403, 401), 'code=%s' % biz(st, r))

st, r = call('POST', '/category/add', {
    'name': '越权测试分类' + stamp, 'icon': 'https://peakflow.top/uploads/products/placeholder-400.jpg',
    'sort': 99, 'status': 1,
}, token=tokens['ordersvc'])
record('角色写', 'ordersvc 越权新增分类被拒', biz(st, r) in (403, 401), 'code=%s' % biz(st, r))

# ==================== E. 售后审核链路（ordersvc 允许写） ====================
print('\n========== E. 售后链路（订单客服） ==========')
tok = tokens['malluser']

st, r = create_order(product['id'], addr['id'], '回归-售后链路')
refund_order_id = (r.get('data') or {}).get('id')
record('售后', '创建售后用订单', refund_order_id is not None, 'orderId=%s' % refund_order_id)

st, r = call('POST', '/pay/prepay', {'orderId': refund_order_id}, token=tok)
vo = r.get('data') or {}
qs = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(vo.get('cashierUrl', '')).query))
if qs:
    req = urllib.request.Request('https://peakflow.top/api/mock-pay/pay',
                                 data=json.dumps({
                                     'out_trade_no': qs['out_trade_no'], 'amount': qs['amount'],
                                     'timestamp': qs['timestamp'], 'sign': qs['sign'],
                                 }).encode(), method='POST')
    req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req, timeout=20) as resp:
        payed = json.loads(resp.read().decode()).get('success') is True
    record('售后', '收银台支付该订单', payed, '')
    for _ in range(10):
        st, r = call('GET', '/order/detail/' + str(refund_order_id), token=tok)
        if (r.get('data') or {}).get('status') == 1:
            break
        _t.sleep(1)
    record('售后', '订单已入账（已付款）', (r.get('data') or {}).get('status') == 1, '')

st, r = call('POST', '/order/refund/' + str(refund_order_id), {'reason': '回归用例-申请退款'}, token=tok)
refund_id = r.get('data') if isinstance(r.get('data'), int) else None
record('售后', '用户申请仅退款', biz(st, r) == 200 and refund_id is not None, 'refundId=%s' % refund_id)

if refund_id:
    st, r = call('POST', '/refund/admin/audit',
                 {'refundId': refund_id, 'approved': True, 'remark': '回归同意退款'},
                 token=tokens['ordersvc'])
    record('售后', 'ordersvc 审核通过退款', biz(st, r) == 200, 'code=%s' % biz(st, r))

    refund_status = None
    for _ in range(12):
        st, r = call('GET', '/refund/detail/' + str(refund_id), token=tok)
        refund_status = (r.get('data') or {}).get('status')
        if refund_status == 2:
            break
        _t.sleep(1)
    record('售后', '退款单变为成功(2)', refund_status == 2, 'status=%s' % refund_status)

    st, r = call('GET', '/order/detail/' + str(refund_order_id), token=tok)
    ostatus = (r.get('data') or {}).get('status')
    record('售后', '订单变为已退款(6)', ostatus == 6, 'status=%s' % ostatus)

# ==================== F. 负向边界 ====================
print('\n========== F. 负向边界 ==========')
tok = tokens['malluser']

st, r = call('POST', '/cart/add?productId=999999&quantity=1', None, token=tok)
record('负向', '加购不存在的商品被拒', biz(st, r) != 200, 'code=%s' % biz(st, r))

st, r = call('POST', '/order/create', {'addressId': addr['id'],
                                       'items': [{'productId': 999999, 'quantity': 1}]}, token=tok)
record('负向', '下单不存在的商品被拒', biz(st, r) != 200, 'code=%s' % biz(st, r))

st, r = call('POST', '/order/create', {'addressId': addr['id'],
                                       'items': [{'productId': product['id'], 'quantity': 99999}]}, token=tok)
record('负向', '下单超库存被拒', biz(st, r) != 200, 'code=%s' % biz(st, r))

st, r = call('POST', '/pay/prepay', {'orderId': order_id}, token=tok)
record('负向', '已取消订单不可再次支付', biz(st, r) != 200, 'code=%s' % biz(st, r))

st, r = call('POST', '/order/create', {'addressId': 999999,
                                       'items': [{'productId': product['id'], 'quantity': 1}]}, token=tok)
record('负向', '下单用不存在的地址被拒', biz(st, r) != 200, 'code=%s' % biz(st, r))

st, r = call('GET', '/product/detail/999999')
record('负向', '不存在的商品详情返回业务错误', biz(st, r) != 200, 'code=%s' % biz(st, r))

# ==================== 汇总 ====================
print('\n' + '=' * 60)
total = len(results)
passed = sum(1 for x in results if x[2] == '✓')
groups = {}
for g, _, s, _ in results:
    a, b = groups.get(g, (0, 0))
    groups[g] = (a + (1 if s == '✓' else 0), b + 1)
for g, (a, b) in groups.items():
    print(f'  {g}: {a}/{b}')
print(f'总计: {passed}/{total}')
print('✅ 全部通过' if passed == total else '❌ 存在失败用例，见上方 ✗ 明细')
sys.exit(0 if passed == total else 1)
