# -*- coding: utf-8 -*-
"""
端到端冒烟测试（阶段 10）

只用标准库，能在两个地方跑：
  · 宿主机：python smoke_test.py                      → 直连 http://localhost:8080
  · 容器里：docker compose exec tester python3 postman/smoke_test.py
                                                      → 由 compose 注入 API_BASE=http://nginx/api

与 newman 的分工：
  newman 跑的是**全量接口回归**（123 请求 / 768 断言，含大量异常分支与越权用例），
  本脚本跑的是**一条主干链路的连通性冒烟**，几十秒出结果，用来确认
  「nginx → 后端 → MySQL/Redis/RocketMQ」整条链路是活的。

覆盖阶段 10 冒烟清单：
  浏览器访问 → 登录 → 商品列表 → 秒杀 → 下单 → 支付 → 退款

退出码：0 = 全通过；1 = 有失败（方便 CI 直接判定）
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

# ---------------------------------------------------------------------------
# 配置：默认值 = 宿主机直连后端。容器里由 compose 覆盖成 http://nginx/api
#   （nginx 的 location /api/ 会把前缀剥掉再转发给 backend1/backend2）
# ---------------------------------------------------------------------------
API_BASE = os.getenv('API_BASE', 'http://localhost:8080').rstrip('/')
# 浏览器入口。API_BASE 形如 http://localhost/api 时，网页入口就是 http://localhost
WEB_BASE = os.getenv('WEB_BASE')
if not WEB_BASE:
    WEB_BASE = API_BASE[:-4] if API_BASE.endswith('/api') else API_BASE

MOCK_PAY_SECRET = os.getenv('MOCK_PAY_SECRET', 'peakflow-mock-pay-secret-key-2026')
ADMIN_USER = os.getenv('ADMIN_USER', 'pfadmin')
ADMIN_PASS = os.getenv('ADMIN_PASS', 'Admin123456')
SECKILL_PRODUCT_ID = os.getenv('SECKILL_PRODUCT_ID', '')

TIMEOUT = 20
PASS, FAIL = 'PASS', 'FAIL'
_results = []


# ---------------------------------------------------------------------------
# HTTP 小工具
# ---------------------------------------------------------------------------
def call(method, path, body=None, token=None, base=None, raw=False):
    """发一个请求，返回 (http_status, payload)。payload 是解析后的 JSON 或字符串。"""
    url = (base or API_BASE) + path
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            text = resp.read().decode('utf-8', 'replace')
            status = resp.status
    except urllib.error.HTTPError as e:
        text = e.read().decode('utf-8', 'replace')
        status = e.code
    if raw:
        return status, text
    try:
        return status, json.loads(text)
    except Exception:
        return status, text


def check(name, ok, detail=''):
    _results.append((name, ok, detail))
    mark = PASS if ok else FAIL
    line = '[%s] %s' % (mark, name)
    if detail:
        line += '  ← ' + str(detail)[:160]
    print(line)
    return ok


def body_ok(resp, step):
    """统一校验 Result 结构：HTTP 200 + 业务码 200。"""
    if not isinstance(resp, dict):
        check(step, False, '响应不是 JSON：%s' % str(resp)[:120])
        return False
    if resp.get('code') != 200:
        check(step, False, '业务码=%s message=%s' % (resp.get('code'), resp.get('message')))
        return False
    return True


def sign(params, secret=MOCK_PAY_SECRET):
    """MockPay 签名：非空参数按 key 升序拼 k=v&k=v，HMAC-SHA256 后 Base64。"""
    items = [(k, v) for k, v in params.items() if v is not None and str(v) != '']
    content = '&'.join('%s=%s' % (k, v) for k, v in sorted(items))
    digest = hmac.new(secret.encode('utf-8'), content.encode('utf-8'), hashlib.sha256).digest()
    return base64.b64encode(digest).decode('utf-8')


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def main():
    print('=' * 74)
    print('PeakFlow 端到端冒烟测试')
    print('  后端入口 : %s' % API_BASE)
    print('  网页入口 : %s' % WEB_BASE)
    print('  时间     : %s' % datetime.now().strftime('%Y-%m-%d %H:%M:%S'))
    print('=' * 74)

    # ---- 0. 浏览器入口：nginx 托管的 SPA，不需要后端参与 ----
    try:
        status, text = call('GET', '/', base=WEB_BASE, raw=True)
        hit_nginx = status == 200 and ('<div id="app"' in text or '<title' in text)
        check('0. 浏览器访问首页', hit_nginx, 'HTTP %s, %d 字节' % (status, len(text)))
    except Exception as e:
        check('0. 浏览器访问首页', False, e)

    # ---- 1. 注册 + 登录（每次用新账号，避免「一人一单」等历史状态干扰）----
    uniq = str(int(time.time()))
    account = 'smoke' + uniq
    password = 'Smoke123456'
    resp = call('POST', '/auth/register', {
        'username': account, 'password': password,
        'email': account + '@smoke.com', 'phone': '13800000000', 'nickname': '冒烟测试用户',
    })
    st, resp = resp[0], resp[1]
    check('1a. 注册新用户 %s' % account, body_ok(resp, '1a. 注册新用户'), 'HTTP %s' % st)

    resp = call('POST', '/auth/login', {'account': account, 'password': password})
    ok = body_ok(resp[1], '1b. 登录')
    token = (resp[1].get('data') or {}).get('token') if ok else None
    check('1b. 登录拿到 token', bool(token), (token or '')[:24] + '...')

    if not token:
        return finish()

    # ---- 2. 收货地址（下单前置）----
    resp = call('POST', '/address/add', {
        'receiverName': '冒烟测试', 'receiverPhone': '13800000000',
        'province': '江苏省', 'city': '南京市', 'district': '鼓楼区',
        'detailAddress': '测试路 1 号',
    }, token=token)
    ok = body_ok(resp[1], '2. 新增收货地址')
    address_id = (resp[1].get('data') or {}).get('id') if ok else None
    if not address_id:
        # 有的实现不回 id，退回地址列表取第一条
        r2 = call('GET', '/address/list', token=token)
        rows = (r2[1].get('data') or []) if isinstance(r2[1], dict) else []
        address_id = rows[0]['id'] if rows else None
    check('2. 拿到 addressId', bool(address_id), address_id)

    if not address_id:
        return finish()

    # ---- 3. 商品列表（公开接口）----
    resp = call('POST', '/product/page', {'pageNum': 1, 'pageSize': 5})
    ok = body_ok(resp[1], '3. 商品列表')
    page = (resp[1].get('data') or {}) if ok else {}
    # 分页结构是 { list, total, pages, pageNum, pageSize }
    records = page.get('list') or page.get('records') or []
    product_id = records[0]['id'] if records else None
    check('3. 商品列表返回数据', bool(records),
          '共 %d 条 / total=%s，productId=%s' % (len(records), page.get('total'), product_id))

    # ---- 4. 秒杀列表 ----
    resp = call('POST', '/seckill/list', {'pageNum': 1, 'pageSize': 10})
    ok = body_ok(resp[1], '4. 秒杀列表')
    sk_page = (resp[1].get('data') or {}) if ok else {}
    sk_records = sk_page.get('list') or sk_page.get('records') or []
    # 秒杀列表项里的 id 就是「秒杀商品 id」，也就是下单接口的 productId
    sk_pid = SECKILL_PRODUCT_ID or (sk_records[0].get('productId') or sk_records[0].get('id') if sk_records else None)
    check('4. 秒杀列表返回数据', bool(sk_records),
          '共 %d 条 / total=%s，秒杀 productId=%s' % (len(sk_records), sk_page.get('total'), sk_pid))

    # ---- 5. 秒杀下单 + 轮询结果（Redis 预扣 → RocketMQ 异步落库）----
    if sk_pid:
        resp = call('POST', '/seckill/order', {'productId': sk_pid, 'addressId': address_id}, token=token)
        ok = body_ok(resp[1], '5a. 秒杀下单')
        # 这个接口的 data 直接就是 requestId 字符串（不是对象）
        d = resp[1].get('data') if ok else None
        request_id = d if isinstance(d, str) else (d or {}).get('requestId')
        if request_id is not None:
            request_id = str(request_id)
        check('5a. 秒杀下单拿到 requestId', bool(request_id), request_id)
        check('5a. 秒杀下单排队中', ok, resp[1].get('message') if ok else resp[1])

        if request_id:
            status_val, detail = None, ''
            for _ in range(20):                 # 最多等 10s
                r = call('POST', '/seckill/result/%s' % request_id, token=token)
                d = (r[1].get('data') or {}) if isinstance(r[1], dict) else {}
                status_val = d.get('status')
                # status：0=排队中 1=成功 2=失败（2 时带 failReason）
                detail = 'status=%s orderNo=%s' % (status_val, d.get('orderNo'))
                if status_val == 2:
                    detail += ' failReason=%s' % d.get('failReason')
                if status_val in (1, 2):        # 已经有结论，不用再等
                    break
                time.sleep(0.5)
            check('5b. 秒杀结果 = 成功（Redis 预扣 → MQ 异步落库）', status_val == 1, detail)
        else:
            check('5b. 秒杀结果', False, '未拿到 requestId')
    else:
        check('5. 秒杀链路', False, '秒杀列表为空，跳过')

    # ---- 6. 普通下单 ----
    #   应付金额从「下单响应」取（data.payAmount），后面模拟支付要按这个金额回调
    order_id, pay_amount = None, None
    if product_id:
        resp = call('POST', '/order/create', {
            'addressId': address_id,
            'items': [{'productId': product_id, 'quantity': 1}],
            'remark': '冒烟测试订单',
        }, token=token)
        ok = body_ok(resp[1], '6. 普通下单')
        d = (resp[1].get('data') or {}) if ok else {}
        order_id = d.get('id') or d.get('orderId')
        pay_amount = d.get('payAmount')
        check('6. 下单拿到 orderId 与应付金额', bool(order_id) and pay_amount is not None,
              'orderId=%s orderNo=%s payAmount=%s' % (order_id, d.get('orderNo'), pay_amount))

    # ---- 7. 支付：预支付 → 收银台回调 → 订单已付款 ----
    if order_id:
        resp = call('POST', '/pay/prepay', {'orderId': order_id}, token=token)
        ok = body_ok(resp[1], '7a. 发起支付')
        d = (resp[1].get('data') or {}) if ok else {}
        out_trade_no = d.get('outTradeNo')
        # 预支付返回的字段叫 amount；万一为空则退回下单时拿到的 payAmount
        pay_amount = d.get('amount', pay_amount)
        cashier_url = d.get('cashierUrl') or ''
        check('7a. 拿到支付单号与金额', bool(out_trade_no) and pay_amount is not None,
              '%s / %s' % (out_trade_no, pay_amount))
        # 收银台地址是**浏览器**要跳转打开的，必须是对外入口（nginx），不能是容器内网地址
        check('7a. 收银台地址走对外入口', 'localhost' in cashier_url and '/api/mock-pay/cashier' in cashier_url,
              cashier_url[:110])

        if out_trade_no and pay_amount is not None:
            ts = str(int(time.time() * 1000))
            payload = {'out_trade_no': out_trade_no, 'amount': str(pay_amount), 'timestamp': ts}
            payload['sign'] = sign(payload)
            # MockPay 是第三方角色，不带我方 token
            resp = call('POST', '/mock-pay/pay', payload)
            ok = isinstance(resp[1], dict) and resp[1].get('success') is True
            check('7b. 模拟支付回调成功', ok, resp[1] if not ok else 'success=true')

            time.sleep(0.8)   # 等回调落库
            resp = call('GET', '/order/detail/%s' % order_id, token=token)
            st_val = ((resp[1].get('data') or {}).get('status')) if isinstance(resp[1], dict) else None
            check('7c. 订单状态 = 已付款', st_val == 1, 'status=%s' % st_val)

    # ---- 8. 退款：申请 → 管理员审核 → 订单已退款 ----
    if order_id:
        resp = call('POST', '/refund/apply', {'orderId': order_id, 'reason': '冒烟测试，申请退款'}, token=token)
        ok = body_ok(resp[1], '8a. 申请退款')
        # 这个接口的 data 既可能是对象，也可能直接是 refundId
        d = resp[1].get('data') if ok else None
        refund_id = d if isinstance(d, (int, str)) and not isinstance(d, bool) else (d or {}).get('id')
        check('8a. 拿到 refundId', bool(refund_id), refund_id)

        if refund_id:
            resp = call('POST', '/auth/login', {'account': ADMIN_USER, 'password': ADMIN_PASS})
            ok = body_ok(resp[1], '8b. 管理员登录')
            admin_token = (resp[1].get('data') or {}).get('token') if ok else None
            check('8b. 管理员 %s 登录' % ADMIN_USER, bool(admin_token),
                  (admin_token or '')[:24] + '...' if admin_token else '')

            if admin_token:
                resp = call('POST', '/refund/admin/audit', {
                    'refundId': refund_id, 'approved': True, 'remark': '冒烟测试，同意退款',
                }, token=admin_token)
                ok = body_ok(resp[1], '8c. 管理员审核通过')
                check('8c. 管理员审核通过（触发自动退款 + 回调）', ok,
                      resp[1].get('message') if ok else resp[1])

                time.sleep(0.8)
                resp = call('GET', '/order/detail/%s' % order_id, token=token)
                st_val = ((resp[1].get('data') or {}).get('status')) if isinstance(resp[1], dict) else None
                check('8d. 订单状态 = 已退款', st_val == 6, 'status=%s' % st_val)

    return finish()


def finish():
    total = len(_results)
    failed = [r for r in _results if not r[1]]
    print('-' * 74)
    print('结果：%d/%d 通过' % (total - len(failed), total))
    if failed:
        print('失败项：')
        for name, _, detail in failed:
            print('  ✗ %s  (%s)' % (name, detail))
    print('=' * 74)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
