#!/usr/bin/env python3
"""清理回归测试残留的「回归临时商品」，走正常业务接口（下架 → 删除），不直接改库。

为什么要走接口而不是 DELETE FROM：
· 直接删库会绕过删除前的占用校验（在途订单 / 退款），可能留下脏引用
· 走接口还能顺带验证「下架→删除」这条业务规则确实可用
"""
import json
import re
import ssl
import subprocess
import urllib.error
import urllib.request

urllib.request.install_opener(
    urllib.request.build_opener(urllib.request.ProxyHandler({})))
SSL = ssl._create_unverified_context()
BASE = 'https://peakflow.top/api'


def call(method, path, body=None, token=None):
    r = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method)
    r.add_header('Content-Type', 'application/json')
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(r, timeout=30, context=SSL) as x:
            return json.loads(x.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode() or '{}')
        except Exception:                                   # noqa: BLE001
            return {'code': e.code}


def sql_rows(q):
    pw = subprocess.run(
        "grep '^DB_PASSWORD=' /opt/PeakFlow/.env | head -1 | cut -d= -f2",
        shell=True, capture_output=True, text=True).stdout.strip()
    cmd = ("docker exec -i -e MYSQL_PWD='{}' peakflow-mysql "
           "mysql -uroot peakflow_prod --default-character-set=utf8mb4 -N -e \"{}\""
           .format(pw, q))
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return [ln for ln in r.stdout.splitlines() if ln.strip()]


# 1. 找出所有测试商品（id / name / status）
rows = sql_rows("SELECT id, name, status FROM product "
                "WHERE name LIKE '回归临时商品%' OR name LIKE '越权测试%' "
                "ORDER BY id;")
targets = []
for ln in rows:
    parts = ln.split('\t')
    if len(parts) >= 3:
        targets.append({'id': int(parts[0]), 'name': parts[1], 'status': int(parts[2])})

print('找到 {} 条测试商品残留：'.format(len(targets)))
for t in targets:
    print('  id={:<4} status={} {}'.format(t['id'], t['status'], t['name']))

if not targets:
    print('无需清理')
    raise SystemExit(0)

# 2. 超管登录
tok = call('POST', '/auth/login', {'account': 'pfadmin', 'password': '2005ww'})['data']['token']

# 3. 逐条：下架 → 删除
ok = fail = 0
for t in targets:
    pid = t['id']
    # 3.1 下架（status=0）。deleteProduct 拒绝删除上架商品，这步是前置条件
    r = call('PUT', '/product/update', {
        'id': pid, 'name': t['name'], 'description': '测试残留清理',
        'image': 'https://peakflow.top/uploads/products/placeholder-800.jpg',
        'price': 1.0, 'stock': 10, 'categoryId': 1, 'status': 0,
    }, tok)
    if r.get('code') != 200:
        print('  ✗ id={} 下架失败: {}'.format(pid, r.get('message')))
        fail += 1
        continue

    # 3.2 删除
    r = call('DELETE', '/product/delete/' + str(pid), None, tok)
    if r.get('code') == 200:
        print('  ✓ id={} 已清理'.format(pid))
        ok += 1
    else:
        print('  ✗ id={} 删除失败: {}'.format(pid, r.get('message')))
        fail += 1

# 4. 复查
left = sql_rows("SELECT id FROM product WHERE name LIKE '回归临时商品%' OR name LIKE '越权测试%';")
print('\n清理结果：成功 {}，失败 {}，库中剩余 {}'.format(ok, fail, len(left)))
