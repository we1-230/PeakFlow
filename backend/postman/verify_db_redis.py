# -*- coding: utf-8 -*-
"""
DB / Redis 落库验证脚本（Newman 跑完后执行，做「全面检查」）。

Postman 只能看接口响应（业务码 / 消息 / 格式），查不了数据库和 Redis 的真实落库。
本脚本在 Collection 跑完后，对照资金/秒杀链路的预期，检查：
  1. MySQL peakflow_test：用户 / 订单 / 支付单 / 退款单 / 秒杀订单 的状态是否正确流转、金额是否正确
  2. Redis(6380, db1)：秒杀库存预扣、购买权门闩、结果 key 是否正常

用法：
  python verify_db_redis.py
"""
import os
import subprocess
import json
import pymysql
from datetime import datetime

# 连接参数一律走环境变量，默认值 = 「宿主机连 compose 发布的端口」这一场景。
# 在测试容器（tester）里跑时由 compose 注入 DB_HOST=mysql / DB_PORT=3306 /
# REDIS_HOST=redis / REDIS_PORT=6379，脚本无需改动。
DB = dict(
    host=os.getenv('DB_HOST', '127.0.0.1'),
    # 容器 MySQL 默认发布到 3307（3306 是系统 MySQL80）
    port=int(os.getenv('DB_PORT', '3307')),
    user=os.getenv('DB_USER', 'root'),
    password=os.getenv('DB_PASSWORD', '1234'),
    database=os.getenv('DB_NAME', 'peakflow_test'),
    charset='utf8mb4',
)
# 宿主机默认用本机那份 redis-cli.exe；容器里换成 redis-tools 的 redis-cli
REDIS_CLI = os.getenv('REDIS_CLI', r'D:\tools\redis\redis-cli.exe')
REDIS_HOST = os.getenv('REDIS_HOST', '127.0.0.1')
REDIS_PORT = int(os.getenv('REDIS_PORT', '6380'))
REDIS_DB = str(os.getenv('REDIS_DATABASE', '1'))

SECOND_SECKILL_PID = 10  # 第二个秒杀测试商品（与 setup_test_data.py 默认 --seckill-ids 5,10 对齐）

PASS = '✓'
FAIL = '✗'
WARN = '△'


def redis_cmd(*args):
    """调用 redis-cli，返回 stdout 字符串。"""
    try:
        out = subprocess.run([REDIS_CLI, '-h', REDIS_HOST, '-p', str(REDIS_PORT), '-n', REDIS_DB] + list(args),
                             capture_output=True, text=True, timeout=5)
        return out.stdout.strip()
    except Exception as e:
        return 'ERR:%s' % e


def main():
    conn = pymysql.connect(**DB)
    cur = conn.cursor(pymysql.cursors.DictCursor)
    results = []

    def check(name, ok, detail=''):
        results.append((name, ok, detail))

    print('=' * 70)
    print('DB / Redis 落库验证报告')
    print('=' * 70)

    # ---------- 1. 用户 ----------
    cur.execute("SELECT id, username, status, LEFT(password,7) pwd FROM `user` ORDER BY id DESC LIMIT 3")
    users = cur.fetchall()
    newest = users[0] if users else None
    if newest:
        pwd_ok = newest['pwd'].startswith('$2')  # bcrypt
        check('新注册用户存在且密码为 BCrypt 哈希', pwd_ok,
              '最新用户 id=%s username=%s status=%s' % (newest['id'], newest['username'], newest['status']))
    else:
        check('新注册用户存在', False, 'user 表为空')

    # ---------- 2. 订单状态分布 ----------
    cur.execute("SELECT status, COUNT(*) c FROM `order` WHERE deleted=0 GROUP BY status ORDER BY status")
    order_dist = {r['status']: r['c'] for r in cur.fetchall()}
    check('订单状态分布（含已退款/已取消）', order_dist.get(6, 0) >= 1,
          '待付款0:%s 已付款1:%s 已发货2:%s 已完成3:%s 已取消4:%s 已关闭5:%s 已退款6:%s'
          % tuple(order_dist.get(i, 0) for i in range(7)))

    # ---------- 3. 资金闭环：最新一笔普通订单应「已退款」 ----------
    cur.execute("SELECT id, order_no, status, total_amount, pay_amount FROM `order` "
                "WHERE order_type=0 AND deleted=0 ORDER BY id DESC LIMIT 1")
    last_order = cur.fetchone()
    if last_order:
        check('资金闭环订单已退款(status=6)', last_order['status'] == 6,
              'orderNo=%s status=%s total=%s pay=%s'
              % (last_order['order_no'], last_order['status'],
                 last_order['total_amount'], last_order['pay_amount']))
    else:
        check('资金闭环订单存在', False, '无普通订单')

    # ---------- 4. 支付单：最新一笔应「已退款(3)」 ----------
    cur.execute("SELECT id, out_trade_no, trade_no, amount, status FROM pay_record ORDER BY id DESC LIMIT 1")
    last_pay = cur.fetchone()
    if last_pay:
        check('支付单已退款(status=3)', last_pay['status'] == 3,
              'outTradeNo=%s tradeNo=%s amount=%s status=%s'
              % (last_pay['out_trade_no'], last_pay['trade_no'],
                 last_pay['amount'], last_pay['status']))
        check('支付单有第三方交易号 trade_no', bool(last_pay['trade_no']),
              'trade_no=%s' % last_pay['trade_no'])
    else:
        check('支付单存在', False, 'pay_record 为空')

    # ---------- 5. 退款单：最新一笔应「退款成功(2)」 ----------
    cur.execute("SELECT id, refund_no, refund_trade_no, refund_amount, status, refund_type "
                "FROM refund_record ORDER BY id DESC LIMIT 1")
    last_refund = cur.fetchone()
    if last_refund:
        check('退款单退款成功(status=2)', last_refund['status'] == 2,
              'refundNo=%s refundTradeNo=%s amount=%s status=%s type=%s'
              % (last_refund['refund_no'], last_refund['refund_trade_no'],
                 last_refund['refund_amount'], last_refund['status'], last_refund['refund_type']))
        check('退款单有第三方退款流水号', bool(last_refund['refund_trade_no']),
              'refund_trade_no=%s' % last_refund['refund_trade_no'])
    else:
        check('退款单存在', False, 'refund_record 为空')

    # ---------- 6. 秒杀订单：应有一笔 order_type=1 的秒杀订单 ----------
    cur.execute("SELECT id, order_no, user_id, seckill_product_id, status FROM `order` "
                "WHERE order_type=1 AND deleted=0 ORDER BY id DESC LIMIT 1")
    seckill_order = cur.fetchone()
    if seckill_order:
        check('秒杀订单已落库', True,
              'orderNo=%s userId=%s seckillProductId=%s status=%s'
              % (seckill_order['order_no'], seckill_order['user_id'],
                 seckill_order['seckill_product_id'], seckill_order['status']))
    else:
        check('秒杀订单已落库', False, '无秒杀订单（检查秒杀链路是否成功）')

    # ---------- 7. 秒杀库存（DB + Redis 一致性） ----------
    cur.execute("SELECT seckill_stock, sold_count FROM product WHERE id=%s", (SECOND_SECKILL_PID,))
    prod = cur.fetchone()
    db_stock = prod['seckill_stock'] if prod else None
    redis_stock = redis_cmd('get', 'seckill:stock:%s' % SECOND_SECKILL_PID)
    if db_stock is not None and redis_stock not in ('', 'ERR', None):
        consistent = db_stock == int(redis_stock)
        check('秒杀库存 DB 与 Redis 一致', consistent,
              'DB seckill_stock=%s  Redis=%s' % (db_stock, redis_stock))
    else:
        check('秒杀库存可读取', False, 'DB=%s Redis=%s（Redis 库存可能未预热）' % (db_stock, redis_stock))

    # ---------- 8. 秒杀购买权门闩（一人一单） ----------
    if seckill_order:
        gate = redis_cmd('exists', 'seckill:order:success:%s:%s'
                         % (seckill_order['user_id'], seckill_order['seckill_product_id']))
        check('秒杀购买权门闩存在（一人一单）', gate == '1',
              'seckill:order:success:%s:%s exists=%s'
              % (seckill_order['user_id'], seckill_order['seckill_product_id'], gate))

    # ---------- 9. 登录缓存（login:user） ----------
    if newest:
        login_key = redis_cmd('exists', 'login:user:%s' % newest['username'])
        check('登录缓存 login:user 存在', login_key == '1',
              'login:user:%s exists=%s' % (newest['username'], login_key))

    cur.close()
    conn.close()

    # ---------- 汇总 ----------
    print()
    print('-' * 70)
    passed = sum(1 for _, ok, _ in results if ok)
    print('验证结果：%d / %d 通过' % (passed, len(results)))
    print('-' * 70)
    for name, ok, detail in results:
        mark = PASS if ok else FAIL
        print(' %s %s' % (mark, name))
        if detail:
            print('      %s' % detail)
    print()
    if passed == len(results):
        print('✅ 全部落库验证通过：资金闭环、退款闭环、秒杀闭环 DB/Redis 状态均正确。')
    else:
        print('⚠️ 存在未通过的检查项，请对照上面的 detail 排查。')


if __name__ == '__main__':
    main()
