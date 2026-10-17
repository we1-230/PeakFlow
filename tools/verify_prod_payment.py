#!/usr/bin/env python3
"""生产环境端到端支付验证（非破坏性：临时账号 + 一笔模拟支付）

完整复刻浏览器行为：
  注册 → 登录 → 浏览商品 → 加收货地址 → 下单 → prepay 拿收银台地址
  → 模拟收银台页面 fetch('pay') 的解析结果 POST /api/mock-pay/pay
  → 轮询支付结果 → 校验订单状态为已付款
"""
import json
import sys
import urllib.parse
import urllib.request

# ⚠️ 本沙箱环境注入了 http_proxy，代理会剥掉 Authorization 头导致 401，
#    必须显式禁用代理直连（对应 curl 的 --noproxy '*'）
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

BASE = 'https://peakflow.top/api'
USERNAME = 'e2e_verify_tmp'
PASSWORD = '2005ww'

step_results = []


def call(method, path, body=None, token=None, ok_codes=(200,)):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read().decode() or '{}')
    if payload.get('code') not in ok_codes:
        print(f'✗ {method} {path} → 业务码 {payload.get("code")}: {payload.get("message")}')
        sys.exit(1)
    return payload.get('data')


def ok(name, detail=''):
    step_results.append((name, '✓', detail))
    print(f'✓ {name} {detail}')


# 1. 注册（已存在则跳过）
reg = None
try:
    reg = call('POST', '/auth/register', {
        'username': USERNAME, 'password': PASSWORD,
        'email': 'e2e_verify_tmp@peakflow.top', 'phone': '13800009999',
        'nickname': 'E2E支付验证',
    }, ok_codes=(200, 3005))  # 3005=已注册过，幂等容忍
    ok('注册临时账号', USERNAME)
except SystemExit:
    print('… 注册返回非 200，尝试直接登录（账号可能已存在）')

# 2. 登录（首次正确密码登录无需验证码）
login = call('POST', '/auth/login', {'account': USERNAME, 'password': PASSWORD})
token = login.get('token') or login.get('jwt') or ''
if not token:
    print('✗ 登录响应中未找到 token，字段：', list(login.keys()))
    sys.exit(1)
ok('登录获取 JWT', f'token 长度 {len(token)}')

# 3. 挑一个普通商品（非秒杀、有库存）
page = call('POST', '/product/page', {'page': 1, 'pageSize': 20})
records = page.get('list') if isinstance(page, dict) else page
product = next(p for p in records if not p.get('isSeckill') and (p.get('stock') or 0) > 0)
ok('选定商品', f"id={product['id']} {product.get('name', '')[:20]} ¥{product.get('price')}")

# 4. 新增收货地址
addr = call('POST', '/address/add', {
    'receiverName': 'E2E验证', 'receiverPhone': '13800009999',
    'province': '上海市', 'city': '上海市', 'district': '浦东新区',
    'detailAddress': 'E2E 自动化验证专用地址（可删除）',
}, token=token, ok_codes=(200, 3014))  # 3014=地址已存在，幂等容忍
# address/add 不回传 id：从列表里找刚加的地址并设为默认（下单要求默认地址）
addr_list = call('GET', '/address/list', token=token)
items = addr_list if isinstance(addr_list, list) else addr_list.get('list', [])
mine = next(a for a in items if a.get('receiverName') == 'E2E验证')
addr_id = mine['id']
call('PUT', f'/address/default/{addr_id}', token=token)
ok('新增收货地址', f'addressId={addr_id}')

# 5. 创建订单
order = call('POST', '/order/create', {
    'addressId': addr_id,
    'items': [{'productId': product['id'], 'quantity': 1}],
    'remark': 'E2E 支付链路验证订单',
}, token=token)
order_id = order.get('id') if isinstance(order, dict) else order
ok('创建订单', f'orderId={order_id}')

# 6. prepay 拿收银台地址（这就是用户浏览器跳转的地址）
prepay = call('POST', '/pay/prepay', {'orderId': order_id}, token=token)
cashier_url = prepay['cashierUrl']
out_trade_no = prepay['outTradeNo']
ok('prepay 创建支付单', f'outTradeNo={out_trade_no}')
print(f'  收银台地址: {cashier_url[:90]}…')

# 7. 关键验证点：复刻收银台页面 fetch('pay') 的解析结果
#    页面挂在 /api/mock-pay/cashier 下 → 相对 'pay' 解析为 /api/mock-pay/pay
pay_url = urllib.parse.urljoin(cashier_url, 'pay')
print(f'  fetch("pay") 解析为: {pay_url}')
assert pay_url.startswith('https://peakflow.top/api/mock-pay/pay'), \
    f'相对路径解析结果异常：{pay_url}'
ok('收银台相对路径解析', '→ /api/mock-pay/pay（不再是根路径 405）')

qs = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(cashier_url).query))
pay_body = {
    'out_trade_no': qs['out_trade_no'],
    'amount': qs['amount'],
    'timestamp': qs['timestamp'],
    'sign': qs['sign'],
}
# mock-pay 是"第三方"接口，响应是 {success, message} 而不是 Result 包装，单独发
req = urllib.request.Request(pay_url, data=json.dumps(pay_body).encode(), method='POST')
req.add_header('Content-Type', 'application/json')
with urllib.request.urlopen(req, timeout=20) as resp:
    pay_resp = json.loads(resp.read().decode())
assert pay_resp.get('success') is True, f'收银台支付失败: {pay_resp}'
ok('收银台「确认支付」', pay_resp.get('message', ''))

# 8. 轮询支付结果（第三方异步回调入账）
import time
status = None
for _ in range(10):
    rec = call('GET', f'/pay/query/{out_trade_no}', token=token)
    status = rec.get('status') if isinstance(rec, dict) else None
    if status == 1:
        break
    time.sleep(1)
detail = call('GET', f'/order/detail/{order_id}', token=token)
order_status = detail.get('status')
assert order_status == 1, f'订单未入账：status={order_status}'
ok('支付回调入账', f'订单状态={order_status}（1=已付款）')

print()
print('=' * 46)
for name, mark, detail in step_results:
    print(f'{mark} {name}  {detail}')
print('=' * 46)
print('✅ 生产端到端支付链路验证全部通过')
